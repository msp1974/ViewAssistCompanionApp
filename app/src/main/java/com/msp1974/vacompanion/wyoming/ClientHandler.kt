package com.msp1974.vacompanion.wyoming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.openwakeword.ONNXModelRunner
import com.msp1974.vacompanion.audio.PCMMediaPlayer
import com.msp1974.vacompanion.audio.VAMediaPlayer
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

class ClientHandler(private val context: Context, private val server: WyomingTCPServer, private val client: Socket) {
    private val log = Logger()
    private val config: APPConfig = APPConfig.getInstance(context)
    private val client_id = client.port
    private val reader: DataInputStream = DataInputStream(client.getInputStream())
    private val writer: DataOutputStream = DataOutputStream(client.getOutputStream())

    private var runClient: Boolean = true
    private var satelliteStatus: SatelliteState = SatelliteState.STOPPED
    private var pipelineStatus: PipelineStatus = PipelineStatus.INACTIVE
    private val connectionID: String = "${client.inetAddress.hostAddress}"

    private var pingTimer: Timer = Timer()


    private var PCMMediaPlayer: PCMMediaPlayer = PCMMediaPlayer(context)
    private var MusicPlayer: VAMediaPlayer = VAMediaPlayer.getInstance(context)

    // Initiate wake word broadcast receiver
    var wakeWordBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (satelliteStatus == SatelliteState.RUNNING) {
                Thread(object : Runnable {
                    override fun run() {
                        MusicPlayer.duckVolume()
                        sendWakeWordDetection()
                        sendStartPipeline()
                    }
                }).start()
            }
        }
    }
    val filter = IntentFilter().apply { addAction(BroadcastSender.WAKE_WORD_DETECTED) }

    fun run() {
        config.connectionCount += 1
        log.d("Client $client_id connected from ${client.inetAddress.hostAddress}. Connections: ${config.connectionCount}")
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

    private fun stop() {
        log.d("Stopping client $client_id connection handler")
        stopIntervalPing()

        if (satelliteStatus == SatelliteState.RUNNING) {
            stopSatellite()
        }
        client.close()
        if (config.connectionCount > 0) {
            config.connectionCount -= 1
        }
        log.w("${client.inetAddress.hostAddress}:$client_id closed the connection.  Connections remaining: ${config.connectionCount}")
    }

    private fun startSatellite() {
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

            if (server.pipelineClient != null) {
                log.d("Satellite taken over by $client_id from ${server.pipelineClient?.client_id}")
                server.pipelineClient = this
                satelliteStatus = SatelliteState.RUNNING
            } else {
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
        log.d("Streaming audio to server for $client_id")
        pipelineStatus = PipelineStatus.LISTENING
        server.requestInputAudioStream()
    }

    private fun releaseInputAudioStream() {
        log.d("Stopping streaming audio to server for $client_id")
        pipelineStatus = PipelineStatus.INACTIVE
        server.releaseInputAudioStream()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun handleEvent(event: WyomingPacket) {

        if (event.type != "ping" && event.type != "pong" && event.type != "audio-chunk") {
            log.d("Received event - $client_id: ${event.toMap()}")
        }

        // Events not requiring running satellite
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
            "run-satellite" -> {
                startSatellite()
            }
        }

        // Events that must have a running satellite to be processed
        if (satelliteStatus == SatelliteState.RUNNING) {
            when (event.type) {
                "pause-satellite" -> {
                    stopSatellite()
                }

                "audio-start" -> {
                    pipelineStatus = PipelineStatus.STREAMING
                    PCMMediaPlayer.play()
                }

                "audio-chunk" -> {
                    PCMMediaPlayer.writeAudio(event.payload)
                }

                "audio-stop" -> {
                    if (PCMMediaPlayer.isPlaying) {
                        PCMMediaPlayer.stop()
                    }
                    pipelineStatus = PipelineStatus.INACTIVE
                    sendEvent(
                        "played",
                    )
                    MusicPlayer.unDuckVolume()

                }

                "transcribe" -> {
                    requestInputAudioStream()
                }

                "transcript" -> {
                    releaseInputAudioStream()
                    val handler = Handler(Looper.getMainLooper())
                    handler.postDelayed({
                        // Unduck volume if no tts audio stream in 1s
                        if (pipelineStatus != PipelineStatus.STREAMING) {
                            MusicPlayer.unDuckVolume()
                        }
                    }, 1000) //
                }

                "voice-stopped" -> {
                    releaseInputAudioStream()
                }

                "error" -> {
                    releaseInputAudioStream()
                    MusicPlayer.unDuckVolume()
                }

                "custom-action" -> {
                    handleCustomAction(event)
                }
            }
        }
    }

    private fun handleCustomAction(event: WyomingPacket) {
        when (event.getProp("action")) {
            "play-media" -> {
                if (event.getProp("payload") != "") {
                    val values = JSONObject(event.getProp("payload"))
                    MusicPlayer.play(values.getString("url"))
                    MusicPlayer.setVolume(values.getInt("volume").toFloat() / 100)
                }
            }

            "play" -> {
                MusicPlayer.resume()
            }

            "pause" -> {
                MusicPlayer.pause()
            }

            "stop" -> {
                MusicPlayer.stop()
            }

            "set-volume" -> {
                if (event.getProp("payload") != "") {
                    val values = JSONObject(event.getProp("payload"))
                    MusicPlayer.setVolume(values.getInt("volume").toFloat() / 100)
                }
            }

            "toast-message" -> {
                if (event.getProp("payload") != "") {
                    val values = JSONObject(event.getProp("payload"))
                    BroadcastSender.sendBroadcast(
                        context,
                        BroadcastSender.TOAST_MESSAGE,
                        values.getString("message")
                    )
                }
            }

            "screen" -> {
                if (event.getProp("payload") != "") {
                    val values = JSONObject(event.getProp("payload"))
                    if (values.getString("action") == "on") {
                        BroadcastSender.sendBroadcast(
                            context,
                            BroadcastSender.SCREEN_ON
                        )
                    } else {
                        BroadcastSender.sendBroadcast(
                            context,
                            BroadcastSender.SCREEN_OFF
                        )
                    }
                }
            }

            "wake" -> {
                thread{
                    MusicPlayer.duckVolume()
                    sendWakeWordDetection()
                    sendStartPipeline()
                }
            }
        }
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
                                addAll(ONNXModelRunner.Companion.getWakeWords().map {
                                    buildJsonObject {
                                        put("name", it)
                                        putJsonObject("attribution") {
                                            put("name", "")
                                            put("url", "")
                                        }
                                        put("installed", true)
                                        putJsonArray("languages") {
                                            addAll(listOf("en"))
                                        }
                                        put("phrase", it)
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
                put("timestamp", Date().toString())
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
    }

    fun sendAudioStop() {
        sendEvent(
            "audio-stop",
            buildJsonObject {
                put("timestamp", Date().toString())
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

    fun sendStatus(data: JsonObject) {
        sendEvent(
            "custom-status",
            data
        )
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
                        log.w("Data not fully received")
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