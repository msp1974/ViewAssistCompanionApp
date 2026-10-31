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
import kotlinx.serialization.json.booleanOrNull
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

    // Sent by every device every few seconds, and when it hears a hello, so the others know who is
    // around and which wake word each listens for (see PeerPresence).
    const val PRESENCE = "presence"

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
 * Wake word arbitration is run by one elected master (see [arbitrateWakeWord]). It is by wake word,
 * not by area: every device that hears a wake word reports it - with its calibrated mic level and
 * detection score - to the master, wherever the device is, and after [DECISION_WINDOW_MS] the master
 * picks one for that wake word by the rules in [WakeWordArbiter] and announces it. Only devices that
 * actually heard it compete. (Ducking is separate and stays within a duck group, the devices' area or
 * custom zone.) Only that device starts a pipeline, and it
 * tells its duck group to duck now and unduck when the pipeline finishes. A report that arrives
 * after the decision is told it lost at once. The master heartbeats; when the heartbeats stop the
 * remaining devices elect a new one.
 *
 * Devices announce themselves ([VacaBroadcastType.PRESENCE]) so none waits for arbitration it
 * doesn't need: one with no other device on the network that listens for the same wake word just
 * answers, and the master decides as soon as every device that could have heard has reported
 * instead of waiting out [DECISION_WINDOW_MS].
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
        const val DATA_LEVEL = "level"
        const val DATA_WAKE_WORD = "wake_word"
        const val DATA_RESPONDING = "responding"

        // How often every device says it is still here. Peers are dropped after PeerPresence.PEER_TIMEOUT_MS.
        private const val PRESENCE_INTERVAL_MS = 10_000L

        // A hello is answered after a random delay up to this, so a room of devices doesn't all reply at once.
        private const val PRESENCE_REPLY_JITTER_MS = 500
        const val DATA_ID = "id"
        const val DATA_WINNER = "winner"
        const val DATA_IDS = "ids"

        // How long the master gathers detections of one wake word before choosing. Audio is kept
        // while it waits, so it costs no speech, only the wait for the pipeline to start - which is
        // why it is short. A device that reports after it has closed is simply told it lost.
        private const val DECISION_WINDOW_MS = 150L

        // How long a device waits for the master's decision before assuming it is gone and going ahead.
        private const val DECISION_TIMEOUT_MS = 1_500L

        private const val HEARTBEAT_INTERVAL_MS = 2_000L
        private const val HEARTBEAT_TIMEOUT_MS = 6_000L

        // How long a freshly started device waits for the master to answer its hello before claiming.
        private const val STARTUP_MASTER_WAIT_MS = 2_500L

        // Devices wait a random time up to this before claiming mastership, so the fastest claims it.
        private const val ELECTION_JITTER_MS = 500

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
        addHandler(VacaBroadcastType.DEVICE_HELLO) {
            if (isMaster) send(VacaBroadcastType.MASTER_HEARTBEAT)
            // ...and tell it who else is here, so it needn't wait to hear from each of us.
            scope.launch {
                delay((0..PRESENCE_REPLY_JITTER_MS).random().milliseconds)
                sendPresence()
            }
        }
        addHandler(VacaBroadcastType.WAKE_DETECTION) { msg ->
            val id = msg.data[DATA_ID]?.jsonPrimitive?.contentOrNull ?: return@addHandler
            val score = msg.data[DATA_SCORE]?.jsonPrimitive?.floatOrNull ?: 0f
            val level = msg.data[DATA_LEVEL]?.jsonPrimitive?.floatOrNull
            val wakeWord = msg.data[DATA_WAKE_WORD]?.jsonPrimitive?.contentOrNull
            val responding = msg.data[DATA_RESPONDING]?.jsonPrimitive?.booleanOrNull ?: false
            if (isMaster) {
                collectDetection(key(wakeWord), WakeWordArbiter.Contender(id, msg.senderId, score, level, wakeWord, responding))
            }
        }
        addHandler(VacaBroadcastType.WAKE_DECISION) { msg ->
            val winner = msg.data[DATA_WINNER]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ids = msg.data[DATA_IDS]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            completeAwaiting(winner, ids)
        }
        addHandler(VacaBroadcastType.DUCK) { msg ->
            // pipeline_completed is always passed on: unducking something that never ducked does nothing.
            if (config.groupDuckingEnabled) {
                config.eventBroadcaster.notifyEvent(Event(EVENT_REMOTE_WAKE_WORD_DETECTED, "", msg))
            }
        }
        addHandler(VacaBroadcastType.PIPELINE_COMPLETED) { msg ->
            config.eventBroadcaster.notifyEvent(Event(EVENT_REMOTE_PIPELINE_COMPLETED, "", msg))
        }

        scope.launch { masterLoop() }
        scope.launch { presenceLoop() }
        scope.launch { listenLoop() }
    }

    private val arbiter = WakeWordArbiter()
    private val presence = PeerPresence()

    @Volatile private var masterId: String? = null
    @Volatile private var lastMasterSeen = 0L
    private val isMaster get() = masterId == config.uuid

    // Master only: detections gathered per wake word (see [key]) while a decision window is open.
    private val pending = HashMap<String, MutableList<WakeWordArbiter.Contender>>()

    /** What arbitration is grouped by: the wake word, case-insensitively. Blank if there isn't one. */
    private fun key(wakeWord: String?) = wakeWord?.trim()?.lowercase().orEmpty()

    // This device's reported detections waiting for the master's decision.
    private val awaiting = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val detectionCounter = AtomicLong()

    /**
     * Reports a wake word detection with its [score] and returns whether this device should start
     * the audio pipeline (Home Assistant only accepts one per wake word).
     *
     * The master collects every report of this wake word, from any area, for [DECISION_WINDOW_MS]
     * and chooses by [WakeWordArbiter]'s rules: the loudest calibrated [levelDb] (the speaker being
     * nearest to it) when it is clearly the loudest, otherwise the device that last answered, then
     * the highest [score], then the lowest device id. Only devices that heard it report, so only
     * they compete, and a device that is responding is not asked about at all unless it heard it too
     * (see [reportInterruption]), when it always wins.
     * The winner tells its duck group (area or custom zone) to duck, and later unducks via
     * `audioPipelineCompleted`. Losers never start a pipeline, so they send nothing.
     *
     * Skipped ([contest] false, or no wake word) the device just ducks and wins. The same happens
     * if no other device listens for this wake word, if no master is known yet, or it doesn't
     * answer, so a dead master never silences a device.
     */
    suspend fun arbitrateWakeWord(score: Float, levelDb: Float? = null, contest: Boolean = true): Boolean {
        val wakeWordKey = key(config.wakeWord)
        if (!contest || !config.wakeWordArbitrationEnabled || wakeWordKey.isEmpty() || masterId == null) {
            sendDuck()
            return true
        }

        // No other device listens for this wake word, so nobody else could have heard it and there
        // is nothing to decide: no round trip to the master and no window to wait out.
        if (presence.competitors(config.wakeWord, config.uuid).isEmpty()) {
            Timber.d("VacaUdpBroadcast: no other device listens for ${config.wakeWord}, answering without arbitration")
            sendDuck()
            return true
        }

        val me = config.uuid
        val id = "$me-${detectionCounter.incrementAndGet()}"
        val decision = CompletableDeferred<String>()
        awaiting[id] = decision
        report(id, score, levelDb, responding = false)
        val winner = withTimeoutOrNull(DECISION_TIMEOUT_MS.milliseconds) { decision.await() }
        awaiting.remove(id)

        if (winner == null) {
            Timber.w("VacaUdpBroadcast: no decision from master $masterId, starting anyway")
            if (!isMaster) lastMasterSeen = 0L // forces a new election
            sendDuck()
            return true
        }
        val won = winner == id
        Timber.i("VacaUdpBroadcast: wake word arbitration ${if (won) "won" else "lost"} (score=$score, level=$levelDb)")
        if (won) sendDuck()
        return won
    }

    private fun sendDuck() {
        if (config.groupDuckingEnabled) send(VacaBroadcastType.DUCK)
    }

    /** Sends this device's report of the wake word to the master, or hands it over if this is the master. */
    private fun report(id: String, score: Float, levelDb: Float?, responding: Boolean) {
        if (isMaster) {
            collectDetection(
                key(config.wakeWord),
                WakeWordArbiter.Contender(id, config.uuid, score, levelDb, config.wakeWord, responding)
            )
        } else {
            send(VacaBroadcastType.WAKE_DETECTION, buildJsonObject {
                put(DATA_ID, JsonPrimitive(id))
                put(DATA_SCORE, JsonPrimitive(score))
                put(DATA_WAKE_WORD, JsonPrimitive(config.wakeWord))
                if (levelDb != null) put(DATA_LEVEL, JsonPrimitive(levelDb))
                if (responding) put(DATA_RESPONDING, JsonPrimitive(true))
            })
        }
    }

    /**
     * The wake word was heard while this device is responding, so it interrupts that response
     * whatever is decided. This tells the master, so the decision for this wake word goes to this
     * device - it always wins - and the other devices that heard it do nothing. It doesn't wait for
     * an answer. Only devices that heard the wake word are told; a responding device that didn't hear
     * it isn't asked to stop.
     */
    fun reportInterruption(score: Float, levelDb: Float?) {
        if (!config.wakeWordArbitrationEnabled || key(config.wakeWord).isEmpty() || masterId == null) return
        // Nobody else listens for this wake word, so there is nobody to tell.
        if (presence.competitors(config.wakeWord, config.uuid).isEmpty()) return
        report("${config.uuid}-${detectionCounter.incrementAndGet()}", score, levelDb, responding = true)
    }

    private fun completeAwaiting(winner: String, ids: List<String>) {
        ids.forEach { awaiting[it]?.complete(winner) }
    }

    /**
     * Master: adds a detection to its wake word's window, opening one (and a decision timer) if needed.
     * One that arrives just after the window closed is told it lost, not made to wait and then go ahead.
     */
    private fun collectDetection(group: String, detection: WakeWordArbiter.Contender) {
        if (group.isEmpty()) return
        var lateWinner: String? = null
        var opensWindow = false
        var everyoneReported = false
        synchronized(pending) {
            val list = pending[group]
            if (list != null) {
                list.add(detection)
            } else {
                lateWinner = arbiter.lateVerdict(group)
                if (lateWinner == null) {
                    pending[group] = mutableListOf(detection)
                    opensWindow = true
                }
            }
            pending[group]?.let { everyoneReported = hasEveryoneReported(it) }
        }
        lateWinner?.let { winner ->
            Timber.i("VacaUdpBroadcast: ${detection.sender} reported after the decision, telling it it lost")
            send(VacaBroadcastType.WAKE_DECISION, buildJsonObject {
                put(DATA_WINNER, JsonPrimitive(winner))
                put(DATA_IDS, buildJsonArray { add(JsonPrimitive(detection.id)) })
            })
            completeAwaiting(winner, listOf(detection.id)) // our own broadcasts don't loop back to us
            return
        }
        if (opensWindow) scope.launch {
            delay(DECISION_WINDOW_MS.milliseconds)
            decide(group)
        }
        // No point waiting for devices that have nothing to report, or for others once a device that
        // is responding has reported, as it wins whatever they say. The window's timer then finds
        // nothing left to decide (a new window can't open for 2 s after a decision).
        if (everyoneReported || detection.responding) decide(group)
    }

    /** True once every device that could have heard this wake word has reported. */
    private fun hasEveryoneReported(reports: List<WakeWordArbiter.Contender>): Boolean {
        val wakeWord = reports.firstNotNullOfOrNull { it.wakeWord }
        val selfCompetes = PeerPresence.competes(config.wakeWord, wakeWord)
        val expected = presence.expectedReporters(wakeWord, config.uuid, selfCompetes)
        return reports.map { it.sender }.toSet().containsAll(expected)
    }

    /** Master: picks the detection that answers for wake word [groupKey] and announces it. */
    private fun decide(groupKey: String) {
        val detections = synchronized(pending) { pending.remove(groupKey) } ?: return
        val decision = arbiter.decide(groupKey, detections) ?: return
        val best = decision.winner
        val winner = decision.winnerId
        val reports = detections.joinToString { d ->
            "${d.sender.take(8)} ${d.levelDb?.let { "%.1f".format(it) } ?: "?"} dB, score ${"%.2f".format(d.score)}" +
                if (d.responding) ", responding" else ""
        }
        Timber.i("VacaUdpBroadcast: decided winner ${best.sender.take(8)}${if (best.responding) " (interrupting its response)" else ""} from ${detections.size} detection(s): $reports")

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
                sendPresence()
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
        // Any message shows the sender is around; presence and detections also say its wake word.
        presence.seen(message.senderId, message.data[DATA_WAKE_WORD]?.jsonPrimitive?.contentOrNull)
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

    /** Tells the other devices this one is here, and which wake word it listens for. */
    private fun sendPresence() {
        send(VacaBroadcastType.PRESENCE, buildJsonObject {
            put(DATA_WAKE_WORD, JsonPrimitive(config.wakeWord))
        })
    }

    private suspend fun presenceLoop() {
        while (scope.isActive) {
            delay(PRESENCE_INTERVAL_MS.milliseconds)
            sendPresence()
        }
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            // Others decide whether we compete with them by our wake word, so say when it changes.
            "wakeWord" -> sendPresence()
            EVENT_PIPELINE_COMPLETED -> {
                send(VacaBroadcastType.PIPELINE_COMPLETED)
            }
        }
    }
}
