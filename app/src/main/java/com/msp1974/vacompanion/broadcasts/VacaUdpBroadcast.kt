package com.msp1974.vacompanion.broadcasts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/** Message types carried by [VacaUdpBroadcast]. Add new ones here. */
object VacaBroadcastType {
    const val WAKE_WORD_DETECTED = "wake_word_detected"
    const val PIPELINE_COMPLETED = "pipeline_completed"
}

/** A validated VACA broadcast received from another device. */
data class VacaBroadcastMessage(
    val type: String,
    val senderId: String,
    val data: JsonObject,
    val sourceAddress: InetAddress,
) {
    /**
     * True if this device should react to the message: it is in the sender's Sendspin group or in
     * the sender's area. A blank group/area never matches, so unconfigured devices stay out.
     * Shared by every player that ducks for other devices' assistant activity.
     */
    fun isRelevantTo(ownSendspinGroup: String, ownArea: String): Boolean {
        val group = sendspinGroup.orEmpty()
        val sender = area.orEmpty()
        return (group.isNotEmpty() && group == ownSendspinGroup) ||
            (sender.isNotEmpty() && sender.equals(ownArea, ignoreCase = true))
    }

    /** Sendspin group the sender belongs to, if any (added to every outgoing message). */
    val sendspinGroup: String?
        get() = data[VacaUdpBroadcast.DATA_SENDSPIN_GROUP]?.jsonPrimitive?.contentOrNull

    /** Home Assistant area the sender is in, if set (added to every outgoing message). */
    val area: String?
        get() = data[VacaUdpBroadcast.DATA_AREA]?.jsonPrimitive?.contentOrNull
}

/**
 * Sends and listens for VACA-to-VACA UDP broadcasts on the local network, so devices in the same
 * room/home can react to each other (e.g. duck music when another device hears a wake word).
 *
 * Wire format is one JSON object per datagram:
 * `{"magic":"VACA","v":1,"type":"...","sender":"<device uuid>","data":{...}}`
 * A datagram is only accepted if it parses, carries [MAGIC] and a supported version, and does not
 * come from this device. Anything else on the port is silently dropped.
 *
 * To add a new broadcast: add a constant to [VacaBroadcastType], call [send] where it should be
 * emitted, and register a handler with [addHandler] where it should be acted on.
 *
 * Bridges to the app's event bus (see [onEventTriggered]) so senders such as the Satellite don't
 * need a reference to this class:
 *  - in:  `wakeWordDetected` / `audioPipelineCompleted` events are broadcast to the network
 *  - out: `remoteWakeWordDetected` / `remotePipelineCompleted` events are fired when received
 */
class VacaUdpBroadcast(
    private val context: Context,
    deviceManager: DeviceManager,
) : EventListener {

    private val config = deviceManager.config
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(VacaBroadcastMessage) -> Unit>>()

    @Volatile private var receiveSocket: DatagramSocket? = null
    @Volatile private var sendSocket: DatagramSocket? = null

    companion object {
        const val PORT = 10950
        const val MAGIC = "VACA"
        const val VERSION = 1
        const val DATA_SENDSPIN_GROUP = "sendspin_group"
        const val DATA_AREA = "area"
        private const val MAX_PACKET_BYTES = 1024
        private const val RESTART_DELAY_MS = 5_000L

        const val DATA_SCORE = "score"

        // How long devices that heard the same wake word wait for each other's detections before
        // agreeing who runs the pipeline. Must exceed the spread between devices' detection times
        // (audio travel + frame processing) plus network latency; it also delays pipeline start.
        private const val ARBITRATION_WINDOW_MS = 300L

        // Set while running, so the satellite can arbitrate without holding a reference.
        @Volatile var instance: VacaUdpBroadcast? = null
            private set

        // Event bus names - local triggers that cause a broadcast to be sent.
        const val EVENT_PIPELINE_COMPLETED = "audioPipelineCompleted"

        // Event bus names - fired when the matching broadcast is received from another device.
        const val EVENT_REMOTE_WAKE_WORD_DETECTED = "remoteWakeWordDetected"
        const val EVENT_REMOTE_PIPELINE_COMPLETED = "remotePipelineCompleted"
    }

    @Volatile private var started = false

    fun start() {
        // BackgroundTaskController.start() can run again on a server restart.
        if (started) return
        started = true
        instance = this
        config.eventBroadcaster.addListener(this)

        addHandler(VacaBroadcastType.WAKE_WORD_DETECTED) { msg ->
            wakeDetections[msg.senderId] = WakeDetection(
                score = msg.data[DATA_SCORE]?.jsonPrimitive?.floatOrNull ?: 0f,
                area = msg.area.orEmpty(),
                arrivedAt = SystemClock.elapsedRealtime(),
            )
            config.eventBroadcaster.notifyEvent(Event(EVENT_REMOTE_WAKE_WORD_DETECTED, "", msg))
        }
        addHandler(VacaBroadcastType.PIPELINE_COMPLETED) { msg ->
            config.eventBroadcaster.notifyEvent(Event(EVENT_REMOTE_PIPELINE_COMPLETED, "", msg))
        }

        scope.launch { listenLoop() }
    }

    private class WakeDetection(val score: Float, val area: String, val arrivedAt: Long)

    // Latest wake word detection heard from each other device.
    private val wakeDetections = ConcurrentHashMap<String, WakeDetection>()

    /**
     * Announces a wake word detection with its [score] and, when several devices in the same area
     * heard it, decides which one runs the audio pipeline (Home Assistant only accepts one).
     * Returns true if this device should start the pipeline.
     *
     * The highest [score] wins - a stronger detection means the speaker is closest - with the
     * lowest device id breaking ties, so every device reaches the same answer from the same set of
     * broadcasts. Losers must not start a pipeline and so never send "pipeline completed", which
     * keeps everyone's music ducked until the winner finishes. Skipped ([contest] false, or no
     * area set) the device just announces and wins.
     */
    suspend fun arbitrateWakeWord(score: Float, contest: Boolean = true): Boolean {
        val announcedAt = SystemClock.elapsedRealtime()
        send(VacaBroadcastType.WAKE_WORD_DETECTED, buildJsonObject { put(DATA_SCORE, JsonPrimitive(score)) })
        val area = config.area
        if (!contest || area.isEmpty()) return true

        // Peers that detected slightly before us have already announced; ones slightly after will.
        delay(ARBITRATION_WINDOW_MS.milliseconds)
        val windowStart = announcedAt - ARBITRATION_WINDOW_MS
        val me = config.uuid
        val lost = wakeDetections.entries.any { (sender, d) ->
            d.arrivedAt >= windowStart && d.area.equals(area, ignoreCase = true) &&
                (d.score > score || (d.score == score && sender < me))
        }
        wakeDetections.entries.removeIf { it.value.arrivedAt < windowStart }
        Timber.d("VacaUdpBroadcast: wake word arbitration ${if (lost) "lost" else "won"} (score=$score)")
        return !lost
    }

    fun shutdown() {
        instance = null
        config.eventBroadcaster.removeListener(this)
        handlers.clear()
        receiveSocket?.close()
        sendSocket?.close()
        scope.cancel()
    }

    /** Registers [handler] for [type]. Handlers run on the listener thread; keep them quick. */
    fun addHandler(type: String, handler: (VacaBroadcastMessage) -> Unit) {
        handlers.getOrPut(type) { CopyOnWriteArrayList() }.add(handler)
    }

    /** Broadcasts a message of [type] (with optional [data]) to the local network. */
    fun send(type: String, data: JsonObject = JsonObject(emptyMap())) {
        scope.launch {
            try {
                val payload = buildJsonObject {
                    put("magic", JsonPrimitive(MAGIC))
                    put("v", JsonPrimitive(VERSION))
                    put("type", JsonPrimitive(type))
                    put("sender", JsonPrimitive(config.uuid))
                    put("data", JsonObject(data + mapOf(
                        DATA_SENDSPIN_GROUP to JsonPrimitive(config.sendspinGroupId),
                        DATA_AREA to JsonPrimitive(config.area),
                    )))
                }.toString().toByteArray()

                val socket = sendSocket ?: DatagramSocket().apply { broadcast = true }.also { sendSocket = it }
                // Subnet broadcast is delivered on networks that drop the limited broadcast address.
                val targets = linkedSetOf(subnetBroadcastAddress(), InetAddress.getByName("255.255.255.255"))
                targets.filterNotNull().forEach { target ->
                    try {
                        socket.send(DatagramPacket(payload, payload.size, target, PORT))
                    } catch (e: Exception) {
                        Timber.d("VacaUdpBroadcast: send to $target failed: ${e.message}")
                    }
                }
                Timber.d("VacaUdpBroadcast: sent $type")
            } catch (e: Exception) {
                Timber.e("VacaUdpBroadcast: send failed: ${e.message}")
            }
        }
    }

    private suspend fun listenLoop() {
        // Restarts after any socket failure (e.g. network change) until shutdown().
        while (scope.isActive) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PORT))
                }
                receiveSocket = socket
                Timber.i("VacaUdpBroadcast: listening on UDP $PORT")
                val buffer = ByteArray(MAX_PACKET_BYTES)
                while (scope.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    handlePacket(packet)
                }
            } catch (e: Exception) {
                if (!scope.isActive) return
                Timber.w("VacaUdpBroadcast: listener error: ${e.message}")
            } finally {
                receiveSocket?.close()
                receiveSocket = null
            }
            delay(RESTART_DELAY_MS.milliseconds)
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val message = parse(packet) ?: return
        if (message.senderId == config.uuid) return
        Timber.d("VacaUdpBroadcast: received ${message.type} from ${message.senderId}")
        handlers[message.type]?.forEach {
            try {
                it(message)
            } catch (e: Exception) {
                Timber.e("VacaUdpBroadcast: handler for ${message.type} failed: ${e.message}")
            }
        }
    }

    /** Returns the message only if this is a well-formed VACA broadcast, otherwise null. */
    private fun parse(packet: DatagramPacket): VacaBroadcastMessage? {
        return try {
            val json = Json.parseToJsonElement(String(packet.data, 0, packet.length)).jsonObject
            if (json["magic"]?.jsonPrimitive?.contentOrNull != MAGIC) return null
            if (json["v"]?.jsonPrimitive?.intOrNull != VERSION) return null
            val type = json["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            val sender = json["sender"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            VacaBroadcastMessage(type, sender, json["data"] as? JsonObject ?: JsonObject(emptyMap()), packet.address)
        } catch (e: Exception) {
            null
        }
    }

    private fun subnetBroadcastAddress(): InetAddress? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return null
            cm.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }
                ?.let { addr ->
                    val prefix = addr.prefixLength
                    val ip = addr.address.address
                    val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
                    val bytes = ByteArray(4) { i ->
                        (ip[i].toInt() or (mask.inv() ushr (24 - 8 * i))).toByte()
                    }
                    InetAddress.getByAddress(bytes)
                }
        } catch (e: Exception) {
            null
        }
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            EVENT_PIPELINE_COMPLETED -> send(VacaBroadcastType.PIPELINE_COMPLETED)
        }
    }
}
