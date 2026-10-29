    package com.msp1974.vacompanion.broadcasts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Message types carried by [VacaUdpBroadcast]. Add new ones here. */
object VacaBroadcastType {
    // Election: the master answers wake word detections and says so every few seconds.
    const val MASTER_CLAIM = "master_claim"
    const val MASTER_HEARTBEAT = "master_heartbeat"

    // Sent by a device when its listener comes up; the master answers with an immediate heartbeat.
    const val DEVICE_HELLO = "device_hello"

    // Wake word arbitration: devices report detections to the master, which announces the winner.
    const val WAKE_DETECTION = "wake_detection"
    const val WAKE_DECISION = "wake_decision"

    // Sent by the device running a pipeline to the others in its duck group (area or custom zone).
    const val DUCK = "duck"
    const val PIPELINE_COMPLETED = "pipeline_completed" // unduck
}

/** A validated VACA broadcast received from another device. */
data class VacaBroadcastMessage(
    val type: String,
    val senderId: String,
    val data: JsonObject,
    val sourceAddress: InetAddress,
) {
    /**
     * True if this device should react to the message: it is in the sender's duck group (see
     * [APPConfig.duckGroupKey]). A blank group never matches, so unconfigured devices stay out.
     * Shared by every player that ducks for other devices' assistant activity.
     */
    fun isRelevantTo(ownDuckGroup: String): Boolean =
        duckGroup.isNotEmpty() && duckGroup == ownDuckGroup

    /** Duck group the sender belongs to, blank if none (added to every outgoing message). */
    val duckGroup: String
        get() = data[VacaUdpBroadcast.DATA_DUCK_GROUP]?.jsonPrimitive?.contentOrNull.orEmpty()
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
 * Wake word arbitration is run by one elected master (see [arbitrateWakeWord]). Every device that
 * hears a wake word reports it to the master; after [DECISION_WINDOW_MS] the master picks the
 * strongest detection per duck group (the devices' area or custom zone) and announces it. Only that
 * device starts a pipeline, and it tells its duck group to duck now and unduck when the pipeline finishes. The master heartbeats; when
 * the heartbeats stop the remaining devices elect a new one.
 *
 * To add a new broadcast: add a constant to [VacaBroadcastType], call [send] where it should be
 * emitted, and register a handler with [addHandler] where it should be acted on.
 *
 * Bridges to the app's event bus (see [onEventTriggered]) so senders such as the Satellite don't
 * need a reference to this class:
 *  - in:  `audioPipelineCompleted` events are broadcast to the network (unduck)
 *  - out: `remoteWakeWordDetected` (duck) / `remotePipelineCompleted` (unduck) events are fired
 *         when the matching broadcast is received
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
        const val DATA_DUCK_GROUP = "duck_group"
        private const val MAX_PACKET_BYTES = 1024
        private const val RESTART_DELAY_MS = 5_000L

        const val DATA_SCORE = "score"
        const val DATA_ID = "id"
        const val DATA_WINNER = "winner"
        const val DATA_IDS = "ids"

        // How long the master gathers detections of one wake word before choosing the strongest.
        // It delays pipeline start, so it only needs to cover the spread between devices' detections.
        private const val DECISION_WINDOW_MS = 300L

        // How long a device waits for the master's decision before assuming it is gone and going ahead.
        private const val DECISION_TIMEOUT_MS = 1_500L

        private const val HEARTBEAT_INTERVAL_MS = 2_000L
        private const val HEARTBEAT_TIMEOUT_MS = 6_000L

        // How long a freshly started device waits for the master to answer its hello before claiming.
        private const val STARTUP_MASTER_WAIT_MS = 2_500L

        // Devices wait a random time up to this before claiming mastership, so the fastest claims it.
        private const val ELECTION_JITTER_MS = 500

        // Failsafe so a pipeline whose completion was never heard doesn't block its duck group forever.
        private const val ACTIVE_PIPELINE_TIMEOUT_MS = 90_000L

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

        addHandler(VacaBroadcastType.MASTER_CLAIM) { msg -> onMasterSeen(msg.senderId) }
        addHandler(VacaBroadcastType.MASTER_HEARTBEAT) { msg -> onMasterSeen(msg.senderId) }
        // A device just came up and doesn't know the master: tell it straight away.
        addHandler(VacaBroadcastType.DEVICE_HELLO) { if (isMaster) send(VacaBroadcastType.MASTER_HEARTBEAT) }
        addHandler(VacaBroadcastType.WAKE_DETECTION) { msg ->
            val id = msg.data[DATA_ID]?.jsonPrimitive?.contentOrNull ?: return@addHandler
            val score = msg.data[DATA_SCORE]?.jsonPrimitive?.floatOrNull ?: 0f
            if (isMaster) collectDetection(msg.duckGroup, Detection(id, msg.senderId, score))
        }
        addHandler(VacaBroadcastType.WAKE_DECISION) { msg ->
            val winner = msg.data[DATA_WINNER]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ids = msg.data[DATA_IDS]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            completeAwaiting(winner, ids)
        }
        addHandler(VacaBroadcastType.DUCK) { msg ->
            activePipelines[msg.senderId] = ActivePipeline(msg.duckGroup, SystemClock.elapsedRealtime())
            config.eventBroadcaster.notifyEvent(Event(EVENT_REMOTE_WAKE_WORD_DETECTED, "", msg))
        }
        addHandler(VacaBroadcastType.PIPELINE_COMPLETED) { msg ->
            activePipelines.remove(msg.senderId)
            config.eventBroadcaster.notifyEvent(Event(EVENT_REMOTE_PIPELINE_COMPLETED, "", msg))
        }

        scope.launch { masterLoop() }
        scope.launch { listenLoop() }
    }

    private class Detection(val id: String, val sender: String, val score: Float)
    private class ActivePipeline(val group: String, val since: Long)

    @Volatile private var masterId: String? = null
    @Volatile private var lastMasterSeen = 0L
    private val isMaster get() = masterId == config.uuid

    // Master only: detections gathered per duck group while a decision window is open.
    private val pending = HashMap<String, MutableList<Detection>>()

    // Devices (by uuid) known to be running a pipeline, with the duck group they claimed.
    private val activePipelines = ConcurrentHashMap<String, ActivePipeline>()

    // This device's reported detections waiting for the master's decision.
    private val awaiting = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val detectionCounter = AtomicLong()

    /**
     * Reports a wake word detection with its [score] and returns whether this device should start
     * the audio pipeline (Home Assistant only accepts one per wake word).
     *
     * The master collects every report for the duck group (the device's area or custom zone, see
     * [APPConfig.duckGroupKey]) for [DECISION_WINDOW_MS] and the highest score wins (a stronger
     * detection means the speaker is closest), the lowest device id breaking ties. It refuses all
     * of them if a different device in the group already has a pipeline running.
     * The winner tells its group to duck, and later unducks via `audioPipelineCompleted`.
     * Losers never start a pipeline, so they send nothing.
     *
     * Skipped ([contest] false, or no duck group) the device just ducks and wins. The same happens
     * if no master is known yet or it doesn't answer, so a dead master never silences a device.
     */
    suspend fun arbitrateWakeWord(score: Float, contest: Boolean = true): Boolean {
        val group = config.duckGroupKey
        if (!contest || group.isEmpty() || masterId == null) {
            sendDuck()
            return true
        }

        val me = config.uuid
        val id = "$me-${detectionCounter.incrementAndGet()}"
        val decision = CompletableDeferred<String>()
        awaiting[id] = decision
        if (isMaster) {
            collectDetection(group, Detection(id, me, score))
        } else {
            send(VacaBroadcastType.WAKE_DETECTION, buildJsonObject {
                put(DATA_ID, JsonPrimitive(id))
                put(DATA_SCORE, JsonPrimitive(score))
            })
        }
        val winner = withTimeoutOrNull(DECISION_TIMEOUT_MS.milliseconds) { decision.await() }
        awaiting.remove(id)

        if (winner == null) {
            Timber.w("VacaUdpBroadcast: no decision from master $masterId, starting anyway")
            if (!isMaster) lastMasterSeen = 0L // forces a new election
            sendDuck()
            return true
        }
        val won = winner == id
        Timber.i("VacaUdpBroadcast: wake word arbitration ${if (won) "won" else "lost"} (score=$score)")
        if (won) sendDuck()
        return won
    }

    private fun sendDuck() {
        activePipelines[config.uuid] = ActivePipeline(config.duckGroupKey, SystemClock.elapsedRealtime())
        send(VacaBroadcastType.DUCK)
    }

    private fun completeAwaiting(winner: String, ids: List<String>) {
        ids.forEach { awaiting[it]?.complete(winner) }
    }

    /** Master: adds a detection to its group's window, opening one (and a decision timer) if needed. */
    private fun collectDetection(group: String, detection: Detection) {
        if (group.isEmpty()) return
        val opensWindow = synchronized(pending) {
            val list = pending.getOrPut(group) { mutableListOf() }
            list.add(detection)
            list.size == 1
        }
        if (opensWindow) scope.launch {
            delay(DECISION_WINDOW_MS.milliseconds)
            decide(group)
        }
    }

    /** Master: picks the strongest detection for [groupKey] and announces it, or refuses all if busy. */
    private fun decide(groupKey: String) {
        val detections = synchronized(pending) { pending.remove(groupKey) } ?: return
        val now = SystemClock.elapsedRealtime()
        activePipelines.entries.removeIf { now - it.value.since > ACTIVE_PIPELINE_TIMEOUT_MS }

        val best = detections.maxWithOrNull(compareBy<Detection> { it.score }.thenByDescending { it.sender }) ?: return
        val busy = activePipelines.any { (sender, p) -> sender != best.sender && p.group == groupKey }
        val winner = if (busy) "" else best.id
        if (!busy) activePipelines[best.sender] = ActivePipeline(groupKey, now)
        Timber.i("VacaUdpBroadcast: decided ${if (busy) "no winner (pipeline active)" else "winner ${best.sender}"} from ${detections.size} detection(s)")

        val ids = detections.map { it.id }
        send(VacaBroadcastType.WAKE_DECISION, buildJsonObject {
            put(DATA_WINNER, JsonPrimitive(winner))
            put(DATA_IDS, buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
        })
        completeAwaiting(winner, ids) // our own broadcasts don't loop back to us
    }

    /** A claim or heartbeat arrived. The lowest id wins if two devices both think they are master. */
    private fun onMasterSeen(sender: String) {
        val current = masterId
        if (current == null || current == sender || sender < current) {
            if (current == config.uuid && sender != current) Timber.i("VacaUdpBroadcast: $sender is master, standing down")
            masterId = sender
            lastMasterSeen = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun masterLoop() {
        // Starting as a listener gives an existing master time to answer our hello before we claim.
        lastMasterSeen = SystemClock.elapsedRealtime() - (HEARTBEAT_TIMEOUT_MS - STARTUP_MASTER_WAIT_MS)
        var lastHeartbeat = 0L
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            if (isMaster) {
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                    send(VacaBroadcastType.MASTER_HEARTBEAT)
                    lastHeartbeat = now
                }
            } else if (now - lastMasterSeen > HEARTBEAT_TIMEOUT_MS) {
                val seen = lastMasterSeen
                masterId = null
                delay((0..ELECTION_JITTER_MS).random().milliseconds)
                if (masterId == null && lastMasterSeen == seen) {
                    Timber.i("VacaUdpBroadcast: no master heard, claiming")
                    masterId = config.uuid
                    lastMasterSeen = SystemClock.elapsedRealtime()
                    send(VacaBroadcastType.MASTER_CLAIM)
                    lastHeartbeat = lastMasterSeen
                }
            }
            delay(1.seconds)
        }
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
                    put("data", JsonObject(data + (DATA_DUCK_GROUP to JsonPrimitive(config.duckGroupKey))))
                }.toString().toByteArray()

                val socket = sendSocket ?: DatagramSocket().apply { broadcast = true }.also { sendSocket = it }
                // Subnet broadcast is delivered on networks that drop the limited broadcast address.
                // Only one target is used: sending to both makes every receiver see each message twice.
                val target = subnetBroadcastAddress() ?: InetAddress.getByName("255.255.255.255")
                try {
                    socket.send(DatagramPacket(payload, payload.size, target, PORT))
                } catch (e: Exception) {
                    Timber.d("VacaUdpBroadcast: send to $target failed: ${e.message}")
                }
                if (type != VacaBroadcastType.MASTER_HEARTBEAT) Timber.d("VacaUdpBroadcast: sent $type")
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
                send(VacaBroadcastType.DEVICE_HELLO)
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
        if (message.type != VacaBroadcastType.MASTER_HEARTBEAT) Timber.d("VacaUdpBroadcast: received ${message.type} from ${message.senderId}")
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
            EVENT_PIPELINE_COMPLETED -> {
                activePipelines.remove(config.uuid)
                send(VacaBroadcastType.PIPELINE_COMPLETED)
            }
        }
    }
}
