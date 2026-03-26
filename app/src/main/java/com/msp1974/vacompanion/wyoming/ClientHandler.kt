package com.msp1974.vacompanion.wyoming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.audio.Alarm
import com.msp1974.vacompanion.audio.PCMMediaPlayer
import com.msp1974.vacompanion.audio.VAMediaPlayer
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.ScreenUtils
import com.msp1974.vacompanion.utils.WakeWords
import com.msp1974.vacompanion.wakeword.microwakeword.providers.AssetWakeWordProvider
import io.github.z4kn4fein.semver.toVersion
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.minusAssign
import kotlin.concurrent.atomics.plusAssign
import kotlin.concurrent.thread

class ClientHandler(private val context: Context, private val server: WyomingTCPServer, private val client: Socket) {
    private val log = Logger()
    private val config: APPConfig = APPConfig.getInstance(context)
    private val client_id = client.port
    private val reader: DataInputStream = DataInputStream(client.getInputStream())
    private val writer: DataOutputStream = DataOutputStream(client.getOutputStream())

    private var handler: Handler = Handler(Looper.getMainLooper())

    private var runClient: Boolean = true
    private var satelliteStatus: SatelliteState = SatelliteState.STOPPED
    private var pipelineStatus: PipelineStatus = PipelineStatus.INACTIVE
    private val connectionID: String = "${client.inetAddress.hostAddress}"

    private var pingTimer: Timer = Timer()

    private var alarmPlayer: Alarm = Alarm(context)
    private var pcmMediaPlayer: PCMMediaPlayer = PCMMediaPlayer(context)
    private var musicPlayer: VAMediaPlayer = VAMediaPlayer.getInstance(context)

    private var expectingTTSResponse: Boolean = false
    private var lastResponseIsQuestion: Boolean = false

    // Initiate wake word broadcast receiver
    var wakeWordBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (satelliteStatus == SatelliteState.RUNNING) {
                Thread(object : Runnable {
                    override fun run() {
                        when {
                            pcmMediaPlayer.isPlaying -> {
                                sendAudioStop()
                                pcmMediaPlayer.stop()
                                volumeDucking("music", false)
                            }
                            alarmPlayer.isSounding -> {
                                actionAlarm(false)
                            }
                            else -> {
                                if (intent.action == BroadcastSender.WAKE_WORD_DETECTED) {
                                    volumeDucking("all", true)
                                    sendWakeWordDetection()
                                    sendStartPipeline()
                                }
                            }
                        }
                    }
                }).start()
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(BroadcastSender.WAKE_WORD_DETECTED)
        addAction(BroadcastSender.STOP_WORD_DETECTED)
    }

    fun run() {
        val connections = config.atomicConnectionCount.incrementAndGet()
        log.d("Client $client_id connected from ${client.inetAddress.hostAddress}. Connections: $connections")
        startIntervalPing()
        while (runClient) {
            try {
                if (reader.available() > 0) {
                    val event: WyomingPacket? = readEvent()
                    if (event != null) {
                        handleEvent(event)
                    }
                }
                if (client.isClosed) {
                    runClient = false
                }
                Thread.sleep(10)
            } catch (ex: Exception) {
                // TODO: Implement exception handling
                log.e("Ending connection $client_id due to client handler exception: $ex")
                runClient = false
            }
        }
        stop()
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun stop() {
        log.d("Stopping client $client_id connection handler")
        stopIntervalPing()

        if (satelliteStatus == SatelliteState.RUNNING) {
            stopSatellite()
        }
        client.close()

        if (config.atomicConnectionCount.get() > 0) {
            config.atomicConnectionCount.andDecrement
        }
        log.w("${client.inetAddress.hostAddress}:$client_id closed the connection.  Connections remaining: ${config.atomicConnectionCount.get()}")
    }

    private fun startSatellite() {

        if (config.version.toVersion() < config.minRequiredApkVersion.toVersion()) {
            log.d("App update needed. App is ${config.version}, Min required is ${config.minRequiredApkVersion}")
            BroadcastSender.sendBroadcast(context, BroadcastSender.VERSION_MISMATCH)
            return
        }

        if (config.pairedDeviceID == "") {
            config.pairedDeviceID = connectionID
        }

        if (config.pairedDeviceID == connectionID) {
            log.d("Starting satellite for ${client.port}")
            LocalBroadcastManager.getInstance(context)
                .registerReceiver(wakeWordBroadcastReceiver, filter)

            // If HA url was blank in config sent from server set it here based on connected IP and port provided
            // in config
            config.homeAssistantConnectedIP = "${client.inetAddress.hostAddress}"

            // Reset status vars
            expectingTTSResponse = false
            lastResponseIsQuestion = false

            if (server.pipelineClient != null) {
                log.d("Satellite taken over by $client_id from ${server.pipelineClient?.client_id}")
                server.pipelineClient = this
                satelliteStatus = SatelliteState.RUNNING
            } else {
                // Ensure alarm is inactive
                actionAlarm(false)

                // Start satellite functions
                server.pipelineClient = this
                satelliteStatus = SatelliteState.RUNNING
                server.satelliteStarted()
                log.d("Satellite started for $client_id")
            }
        } else {
            log.i("Invalid connection (${client.inetAddress.hostAddress}:$client_id) attempting to start satellite!")
            log.i("Aborting connection")
            stop()
        }
        config.isRunning = satelliteStatus == SatelliteState.RUNNING
    }

    private fun stopSatellite() {
        log.d("Stopping satellite for $client_id")
        LocalBroadcastManager.getInstance(context).unregisterReceiver(wakeWordBroadcastReceiver)
        if (server.pipelineClient == this) {
            if (pipelineStatus == PipelineStatus.LISTENING) {
                releaseInputAudioStream()
            }

            // Stop media players
            actionAlarm(false)
            musicPlayer.stop()

            pipelineStatus = PipelineStatus.INACTIVE
            satelliteStatus = SatelliteState.STOPPED
            server.pipelineClient = null
            config.homeAssistantConnectedIP = ""
            server.satelliteStopped()
        } else {
            log.e("Closing orphaned satellite connection - $client_id")
        }
        runClient = false
        log.d("Satellite stopped")
        config.isRunning = satelliteStatus == SatelliteState.RUNNING
    }

    private fun requestInputAudioStream() {
        if (pipelineStatus != PipelineStatus.LISTENING) {
            log.d("Streaming audio to server for $client_id")
            pipelineStatus = PipelineStatus.LISTENING
            server.requestInputAudioStream()
        }
    }

    private fun releaseInputAudioStream() {
        if (pipelineStatus != PipelineStatus.INACTIVE) {
            log.d("Stopping streaming audio to server for $client_id")
            pipelineStatus = PipelineStatus.INACTIVE
            server.releaseInputAudioStream()
        }
    }

    private fun handleEvent(event: WyomingPacket) {

        if (event.type != "ping" && event.type != "pong" && event.type != "audio-chunk") {
            log.d("Received event - $client_id: ${event.toMap()}")
        }

        // Events not requiring running satellite
        try {
            when (event.type) {
                "ping" -> {
                    sendPong()
                }
                "describe" -> {
                    sendInfo()
                }
                "custom-settings" -> {
                    config.processSettings(event.getProp("settings"))
                }
                "capabilities" -> {
                    sendCapabilities()
                }
                "run-satellite" -> {
                    startSatellite()
                }
                "custom-event" -> {
                    handleCustomEvent(event)
                }
            }

            // Events that must have a running satellite to be processed
            if (satelliteStatus == SatelliteState.RUNNING) {
                when (event.type) {
                    "pause-satellite" -> {
                        stopSatellite()
                    }

                    "transcribe" -> {
                        // Sent when requesting voice command
                        volumeDucking("all", true)
                        requestInputAudioStream()
                        setPipelineNextStageTimeout(10)
                    }

                    "voice-started" -> {
                        // Sent when detected voice command started
                        setPipelineNextStageTimeout(30)
                    }

                    "voice-stopped" -> {
                        // Sent when detected voice command stopped
                        setPipelineNextStageTimeout(15)
                    }

                    "transcript" -> {
                        // Sent when STT converted voice command to text
                        releaseInputAudioStream()
                        if (event.getProp("text").lowercase().contains("never mind")) {
                            volumeDucking("all", false)
                        } else {
                            // If no response from conversation engine in 10s, timeout
                            setPipelineNextStageTimeout(10)
                        }
                    }

                    "synthesize" -> {
                        // Sent when conversation engine sent response to command
                        lastResponseIsQuestion =
                            (event.getProp("text").replace("\n", "").endsWith("?"))
                        expectingTTSResponse = true
                        setPipelineNextStageTimeout(10)
                    }

                    "pipeline-ended" -> {
                        // Sent when pipeline has finished
                        if (!expectingTTSResponse) {
                            cancelPipelineNextStageTimeout()
                            volumeDucking("all", false)
                        }
                        if (pipelineStatus != PipelineStatus.STREAMING) {
                            releaseInputAudioStream()
                        }
                    }

                    "audio-start" -> {
                        // Sent when audio stream about to start
                        expectingTTSResponse = false  // This is it so reset expecting
                        cancelPipelineNextStageTimeout() // Playing audio, cancel any timeout
                        pipelineStatus = PipelineStatus.STREAMING
                        volumeDucking("all", true)  // Duck here if announcement
                        pcmMediaPlayer.play()
                    }

                    "audio-chunk" -> {
                        // Audio chunk
                        if (pcmMediaPlayer.isPlaying) {
                            pcmMediaPlayer.writeAudio(event.payload)
                        }
                    }

                    "audio-stop" -> {
                        // Sent when all audio chunks sent
                        if (pcmMediaPlayer.isPlaying) {
                            pcmMediaPlayer.stop()
                        }
                        pipelineStatus = PipelineStatus.INACTIVE
                        sendEvent(
                            "played",
                        )

                        if (config.continueConversation || lastResponseIsQuestion) {
                            sendStartPipeline()
                        } else {
                            setPipelineNextStageTimeout(2)
                        }

                    }

                    "error" -> {
                        config.eventBroadcaster.notifyEvent(Event("recognitionError", "", event.getProp("code")))
                        resetPipeline()
                    }

                    "custom-action" -> {
                        handleCustomAction(event)
                    }

                    "timer-finished" -> {
                        actionAlarm(true)
                    }
                }
            }
        } catch (ex: Exception) {
            log.e("Error handling event: $ex")
            ex.printStackTrace()
        }
    }

    private fun setPipelineNextStageTimeout(duration: Int) {
        cancelPipelineNextStageTimeout()
        handler.postDelayed({
            handlePipelineTimeout()
        }, duration * 1000L)
    }

    private fun cancelPipelineNextStageTimeout() {
        try {
            handler.removeCallbacksAndMessages(null)
        } catch (ex: Exception) {}
    }

    private fun handlePipelineTimeout() {
        log.d("Pipeline timed out")
        resetPipeline()
    }

    private fun resetPipeline() {
        expectingTTSResponse = false

        volumeDucking("all", false)

        if (pipelineStatus != PipelineStatus.STREAMING) {
            releaseInputAudioStream()
        }
        sendAudioStop()
    }

    private fun handleCustomEvent(event: WyomingPacket) {
        when (event.getProp("event_type")) {
            "action" -> {
                handleCustomAction(event)
            }
            "settings" -> {
                config.processSettings(event.getProp("settings"))
            }
            "capabilities" -> {
                sendCapabilities()
            }
        }
    }

    private fun handleCustomAction(event: WyomingPacket) {
        when (event.getProp("action")) {
            "play-media" -> {
                if (event.getProp("payload") != "") {
                    val values = JSONObject(event.getProp("payload"))
                    musicPlayer.play(values.getString("url"))
                    musicPlayer.setVolume(values.getInt("volume"))
                }
            }

            "play" -> {
                musicPlayer.resume()
            }

            "pause" -> {
                musicPlayer.pause()
            }

            "stop" -> {
                musicPlayer.stop()
            }

            "set-volume" -> {
                if (event.getProp("payload") != "") {
                    val values = JSONObject(event.getProp("payload"))
                    musicPlayer.setVolume(values.getInt("volume"))
                }
            }

            "toast-message" -> {
                if (event.getProp("payload") != "") {
                    try {
                        val values = JSONObject(event.getProp("payload"))
                        BroadcastSender.sendBroadcast(
                            context,
                            BroadcastSender.TOAST_MESSAGE,
                            values.getString("message")
                        )
                    } catch (ex: Exception) {
                        log.e("Error sending toast message: $ex")
                    }
                }
            }

            "refresh" -> {
                config.eventBroadcaster.notifyEvent(Event("refresh", "", ""))
            }

            "screen-wake" -> {
                config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
            }

            "screen-sleep" -> {
                config.eventBroadcaster.notifyEvent(Event("screenSleep", "", ""))
            }
            "wake" -> {
                config.eventBroadcaster.notifyEvent(Event("wakeWordTrigger", "", ""))
            }
            "alarm" -> {
                if (event.getProp("payload") != "") {
                    val values = JSONObject(event.getProp("payload"))
                    val active = try {
                        values.getBoolean("activate")
                    } catch (ex: JSONException) {
                        false
                    }
                    val url = try {
                        values.getString("url")
                    } catch (ex: JSONException) {
                        ""
                    }
                    if (active) {
                        actionAlarm(true, url)
                    } else {
                        actionAlarm(false)
                    }
                }
            }
        }
    }

    private fun volumeDucking(type: String, active: Boolean) {
        if (active) {
            if (type == "alarm") {
                alarmPlayer.duckVolume()
            } else if (type == "music") {
                musicPlayer.duckVolume()
            } else {
                alarmPlayer.duckVolume()
                musicPlayer.duckVolume()
            }
        } else {
            if (type == "alarm") {
                alarmPlayer.unDuckVolume()
            } else if (type == "music") {
                musicPlayer.unDuckVolume()
            } else {
                alarmPlayer.unDuckVolume()
                if (!alarmPlayer.isSounding) {
                    musicPlayer.unDuckVolume()
                }
            }

        }
    }

    private fun actionAlarm(enable: Boolean, url: String = "") {
        if (enable) {
            volumeDucking("music", true)
            alarmPlayer.startAlarm(url)
            config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
        } else {
            alarmPlayer.stopAlarm()
            volumeDucking("music", false)
        }
        sendSettingChange("alarm", enable)
    }

    private fun startIntervalPing() {
        pingTimer.schedule(object: TimerTask() {
            override fun run() {
                sendEvent(
                    "ping",
                    buildJsonObject {
                        put("text", "")
                    }
                )
            }
        },0,2000)
    }

    private fun stopIntervalPing() {
        pingTimer.cancel()
    }

    fun sendPong() {
        sendEvent(
            "pong",
            buildJsonObject {
                put("text", "")
            }
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun sendInfo() {
        val owwWakeWords = WakeWords(context).getWakeWords()
        val mwwWakeWords = listOf("alexa","hey_home_assistant","hey_jarvis","hey_luna","hey_mycroft","okay_computer","okay_nabu")
        sendEvent(
            "info",
            buildJsonObject {
                put("version", config.version)
                putJsonArray("asr") {}
                putJsonArray("tts") {}
                putJsonArray("handle") {}
                putJsonArray("intent") {}
                putJsonArray("wake") {
                    add(
                        buildJsonObject {
                            put("name", "available_wake_words")
                            putJsonObject("attribution") {
                                put("name", "")
                                put("url", "")
                            }
                            put("installed", true)
                            putJsonArray("models") {
                                addAll(owwWakeWords.map {
                                    buildJsonObject {
                                        put("name", it.key)
                                        putJsonObject("attribution") {
                                            put("name", "openwakeword")
                                            put("url", "")
                                        }
                                        put("installed", true)
                                        putJsonArray("languages") {
                                            addAll(listOf("en"))
                                        }
                                        put("phrase", it.value.name)
                                    }
                                })
                                addAll(mwwWakeWords.map {
                                    buildJsonObject {
                                        put("name", it)
                                        putJsonObject("attribution") {
                                            put("name", "microwakeword")
                                            put("url", "")
                                        }
                                        put("installed", true)
                                        putJsonArray("languages") {
                                            addAll(listOf("en"))
                                        }
                                        put("phrase", it.replace("_", " "))
                                    }
                                })
                            }
                        }
                    )
                }
                putJsonArray("stt") {}

                putJsonObject("satellite") {
                    put("name", "VACA ${config.uuid}")
                    putJsonObject("attribution") {
                        put("name", "")
                        put("url", "")
                    }
                    put("installed", true)
                    put("description", "View Assist Companion App")
                    put("version", config.version)
                    put("area", "")
                    put("has_vad", false)
                    putJsonObject("snd_format") {
                        put("channels", 1)
                        put("rate", 16000)
                        put("width", 2)
                    }
                    putJsonArray("active_wake_words") {
                        addAll(listOf(config.wakeWord))
                    }
                    put("max_active_wake_words", 1)
                }
            }
        )
    }

    fun sendWakeWordDetection() {
        //status.pipelineStatus = PipelineStatus.LISTENING
        sendEvent(
            "detection",
            buildJsonObject {
                put("name", config.wakeWord)
                put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                put("speaker", "")
            }
        )
    }

    fun sendStartPipeline() {
        sendEvent(
            "run-pipeline",
            buildJsonObject {
                put("name", "VACA ${config.uuid}")
                put("start_stage", "asr")
                put("end_stage", "tts")
                put("restart_on_end", false)
                putJsonObject("snd_format") {
                    put("rate", config.sampleRate)
                    put("width", config.audioWidth)
                    put("channels", config.audioChannels)
                }
            }
        )
        lastResponseIsQuestion = false
    }

    fun sendAudioStop() {
        sendEvent(
            "audio-stop",
            buildJsonObject {
                put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            }
        )
    }

    fun sendAudio(audio: ByteArray) {
        val data = buildJsonObject {
            put("rate", config.sampleRate)
            put("width", config.audioWidth)
            put("channels", config.audioChannels)
        }
        val event = WyomingPacket(JSONObject(mapOf("type" to "audio-chunk", "data" to JSONObject(data.toString()))))
        event.payload = audio

        try {
            writeEvent(event)
        } catch (ex: Exception) {
            log.e("Error sending audio event: $ex")
        }
    }

    fun sendSettingChange(name: String, value: String) {
        sendCustomEvent(
            "settings",
            buildJsonObject {
            put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            putJsonObject("settings") {
                put(name, value)
            }
        })
    }

    fun sendSettingChange(name: String, value: Boolean) {
        sendCustomEvent(
            "settings",
            buildJsonObject {
                put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                putJsonObject("settings") {
                    put(name, value)
                }
            })
    }

    fun sendSettingChange(name: String, value: Int) {
        sendCustomEvent(
            "settings",
            buildJsonObject {
                put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                putJsonObject("settings") {
                    put(name, value)
                }
            })
    }


    fun sendStatus(data: JsonObject) {
        sendCustomEvent(
            "status",
            data
        )
    }

    fun sendCapabilities() {
        val data = DeviceCapabilitiesManager.toJson(server.deviceInfo).toMap()
        sendCustomEvent("capabilities", buildJsonObject {
            for (key in data.keys) {
                put(key, data[key] as JsonElement)
            }
        })
    }

    fun sendCustomEvent(type: String, data: JsonObject) {
        val customEventData = buildJsonObject {
            put("event_type", type)
            put("data", data)
        }
        sendEvent("custom-event", customEventData)
    }

    fun sendEvent(type: String, data: JsonObject = buildJsonObject {  }) {
        try {
            val event = WyomingPacket(JSONObject(mapOf("type" to type, "data" to JSONObject(data.toString()))))
            writeEvent(event)
        } catch (ex: Exception) {
            log.e("Error sending event: $type - $ex")
        }
    }

    private fun readEvent(): WyomingPacket? {
        try {
            val jsonString = StringBuilder()
            var jsonLine = reader.read()
            while (jsonLine != '\n'.code) {
                jsonString.append(jsonLine.toChar())
                jsonLine = reader.read()
            }
            if (jsonString.isEmpty()) {
                return null
            }

            val eventDict = JSONObject(jsonString.toString())

            if (!eventDict.has("type")) {
                return null
            }
            // In wyoming 1.7.1 data can be part of main message
            if (!eventDict.has("data")) {
                var dataLength = 0
                if (eventDict.has("data_length")) {
                    dataLength = eventDict.getInt("data_length")
                }
                // Read data
                if (dataLength != 0) {
                    val dataBytes = ByteArray(dataLength)
                    var i = 0
                    while (reader.available() < dataLength && i < 100) {
                        Thread.sleep(10)
                        i++
                    }
                    reader.read(dataBytes, 0, dataLength)
                    eventDict.put("data", JSONObject(String(dataBytes)))
                } else {
                    eventDict.put("data", JSONObject())
                }
            }

            val wyomingPacket = WyomingPacket(eventDict)

            // Read payload
            var payloadLength: Int = 0
            if (eventDict.has("payload_length")) {
                payloadLength = eventDict.getInt("payload_length")
            }

            if (payloadLength != 0) {
                val payloadBytes = ByteArray(payloadLength)
                var i = 0
                while (reader.available() < payloadLength && i < 100) {
                    log.w("Payload not fully received")
                    Thread.sleep(10)
                    i++
                }
                reader.read(payloadBytes, 0, payloadLength)
                wyomingPacket.payload = payloadBytes
            }
            return wyomingPacket

        } catch (ex: Exception) {
            log.e("Event read exception ${ex.toString().substring(0, ex.toString().length.coerceAtMost(50))}")
        }
        return null
    }

    private fun writeEvent(p: WyomingPacket) {
        if (p.type != "ping" && p.type != "pong" && p.type != "audio-chunk") {
            log.d("Sending to $client_id: ${p.toMap()}")
        }
        val eventDict: MutableMap<String, Any> = p.toMap()
        eventDict["version"] = config.version

        val dataDict: JSONObject = eventDict["data"] as JSONObject
        eventDict -= "data"

        var dataBytes = ByteArray(0)
        if (dataDict.length() > 0) {
            dataBytes = dataDict.toString().toByteArray(Charset.defaultCharset())
            eventDict["data_length"] = dataBytes.size
        }

        if (p.payload.isNotEmpty()) {
            eventDict["payload_length"] = p.payload.size
        }

        var jsonLine = (eventDict as Map<*, *>?)?.let { JSONObject(it).toString() }
        jsonLine += '\n'

        try {
            writer.write(jsonLine.toByteArray(Charset.defaultCharset()))

            if (dataBytes.isNotEmpty()) {
                writer.write(dataBytes)
            }

            if (p.payload.isNotEmpty()) {
                writer.write(p.payload)
            }
            writer.flush()
        } catch (ex: SocketException) {
            log.e("Error sending event: $ex. Likely just a closed socket and not an error!")
            runClient = false
        } catch (ex: Exception) {
            log.e("Unknown error sending event: $ex")
        }

    }



}