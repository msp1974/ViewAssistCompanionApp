package com.msp1974.vacompanion

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.addJsonArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.util.*
import kotlin.concurrent.thread
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

interface wyomingCallback {
    fun onConnected()
    fun onDisconnected()
    fun onStartStreaming()
    fun onStopStreaming()
}

class WyomingPacket (event: JSONObject) {
    val type: String = event.getString("type")
    private var data: JSONObject = event.getJSONObject("data")
    var payload: ByteArray = ByteArray(0)

    fun getProp(prop: String): String {
        return data.getString(prop)
    }

    fun setProp(prop: String, value: String) {
        data.put(prop, value)
    }

    fun getDataLength(): Int {
        return data.toString().length
    }

    fun toMap(): MutableMap<String, Any> {
        return mutableMapOf("type" to type, "data" to data)
    }

}

class WyomingTCPServer (val context: Context, val port: Int, val cbCallback: wyomingCallback){
    var runServer: Boolean = true
    var pipelineClient: ClientHandler? = null

    fun start() {
        try {
            Global.status.connectionState = "Disconnected"
            val server = ServerSocket(port)
            Global.log.i("Server is running on port ${server.localPort}")

            while (runServer) {
                val client = server.accept()
                Global.log.i("Client connected: ${client.inetAddress.hostAddress}")

                // Run client in it's own thread.
                thread { ClientHandler(context, this, client).run() }
            }
        } catch (e: Exception) {
            Global.log.e("Server exception: $e")
        }

    }

    fun stop() {
        runServer = false
    }

    fun sendAudio(audio: ByteArray) {
        pipelineClient?.sendAudio(audio)
    }

    fun startStreaming() {
        cbCallback.onStartStreaming()
    }

    fun stopStreaming() {
        cbCallback.onStopStreaming()
    }

    fun satelliteStarted() {
        cbCallback.onConnected()
    }

    fun satelliteStopped() {
        cbCallback.onDisconnected()
    }

}

class ClientHandler(context: Context, private val server: WyomingTCPServer, private val client: Socket) {
    private val config: Config = Global.config
    private val status: Status = Global.status
    private val reader: DataInputStream = DataInputStream(client.getInputStream())
    private val writer: DataOutputStream = DataOutputStream(client.getOutputStream())
    private var running: Boolean = false
    private var PCMMediaPlayer: PCMMediaPlayer = PCMMediaPlayer(context)
    private var MusicPlayer: MusicPlayer = MusicPlayer()
    private var sendPings: Boolean = true
    private var pingTimer: Timer = Timer()

    fun run() {
        running = true
        while (running) {
            try {
                if (reader.available() > 0) {
                    val event: WyomingPacket? = readEvent()
                    if (event != null) {
                        handleEvent(event)
                    }
                }
            } catch (ex: Exception) {
                // TODO: Implement exception handling
                Global.log.e("Client handler exception: $ex")
                shutdown()
            } finally {

            }

        }
    }

    private fun intervalPing() {
        pingTimer.schedule(object: TimerTask() {
            override fun run() {
                sendEvent(
                    "ping",
                    buildJsonObject {
                        put("text", "")
                    }
                )
            }
        },0,3000)
    }

    fun sendDetection() {
        //status.pipelineStatus = PipelineStatus.LISTENING
        sendEvent(
            "detection",
            buildJsonObject {
                put("name", Global.config.wakeword)
                put("timestamp", Date().toString())
                put("speaker", "")
            }
        )
    }

    fun startPipeline() {
        sendEvent(
            "run-pipeline",
            buildJsonObject {
                put("name", "VA Wyoming ${Global.config.uuid}")
                put("start_stage", "asr")
                put("end_stage", "tts")
                put("restart_on_end", false)
                putJsonObject("snd_format") {
                    put("rate", Global.config.sampleRate)
                    put("width", Global.config.audioWidth)
                    put("channels", Global.config.audioChannels)
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

    @OptIn(ExperimentalSerializationApi::class)
    private fun handleEvent(event: WyomingPacket) {

        if (event.type != "ping" && event.type != "pong" && event.type != "audio-chunk") {
            Global.log.d("Received event: ${event.toMap()}")
        }

        when (event.type) {
            "ping" -> {
                sendEvent(
                    "pong",
                    buildJsonObject {
                        put("text", "")
                    }
                )
            }

            "custom-settings" -> {
                config.processSettings(event.getProp("settings"))
            }

            "media-control" -> {
                when (event.getProp("action")) {
                    "play-media" -> {
                        MusicPlayer.play(event.getProp("value"))
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
                    "volume" -> {
                        MusicPlayer.setVolume(event.getProp("value").toFloat() / 100)
                    }
                }
            }

            "describe" -> {
                sendEvent(
                    "info",
                    buildJsonObject {
                        put("version", "1.5.4")
                        putJsonArray("asr") {}
                        putJsonArray("tts") {}
                        putJsonArray("handle") {}
                        putJsonArray("intent") {}
                        putJsonArray("wake") {
                            //addJsonArray {}
                        }
                        putJsonObject("satellite") {
                            put("name", "VA Wyoming ${Global.config.uuid}")
                            putJsonObject("attribution") {
                                put("name", "")
                                put("url", "")
                            }
                            put("installed", true)
                            put("description", "ViewAssist Wyoming Companion App")
                            put("version", Global.config.version)
                            put("area", "")
                            put("has_vad", false)
                            putJsonObject("snd_format") {
                                put("channels", 1)
                                put("rate", 16000)
                                put("width", 2)
                            }
                            putJsonArray("active_wake_words") {
                                addAll(getWakeWords())
                            }
                            put("max_active_wake_words", 1)

                        }
                    }
                )
            }

            "run-satellite" -> {
                config.haURL = "http://" + client.inetAddress.hostAddress + ':' + config.haPort
                server.pipelineClient = this
                sendPings = true
                intervalPing()
                if (status.connectionState == "Connected") {
                    status.connectionState = "Disconnected"
                }
                status.connectionState = "Connected"
                server.satelliteStarted()
            }

            "pause-satellite" -> {
                sendPings = false
                server.satelliteStopped()
                status.connectionState = "Disconnected"
                status.pipelineStatus = PipelineStatus.INACTIVE
            }

            "audio-start" -> {
                status.pipelineStatus = PipelineStatus.STREAMING
                PCMMediaPlayer.play()
            }

            "audio-chunk" -> {
                PCMMediaPlayer.writeAudio(event.payload)
            }

            "audio-stop" -> {
                if (PCMMediaPlayer.isPlaying) {
                    PCMMediaPlayer.stop()
                }
                sendEvent(
                    "played",
                )
                status.pipelineStatus = PipelineStatus.INACTIVE
            }

            "transcribe" -> {
                status.pipelineStatus = PipelineStatus.LISTENING
                server.startStreaming()
            }

            "transcript" -> {
                status.pipelineStatus = PipelineStatus.INACTIVE
                server.stopStreaming()
            }

            "voice-stopped" -> {
                status.pipelineStatus = PipelineStatus.INACTIVE
                server.stopStreaming()
            }

            "error" -> {
                status.pipelineStatus = PipelineStatus.INACTIVE
            }
        }
    }

    private fun getWakeWords(): List<String> {
        return enumValues<SupportedWakewords>().map { it.name }
    }

    fun sendEvent(type: String, data: JsonObject = buildJsonObject {  }) {
        val event = WyomingPacket(JSONObject(mapOf("type" to type, "data" to JSONObject(data.toString()))))
        try {
            writeEvent(event)
        } catch (ex: Exception) {
            Global.log.e("Error sending event: $type - $ex")
        }
    }

    fun sendAudio(audio: ByteArray) {
        val eventData = """{
                "rate": 16000,
                "width": 2,
                "channels": 1
            }"""
        val event = WyomingPacket(JSONObject(mapOf("type" to "audio-chunk", "data" to JSONObject(eventData))))
        event.payload = audio

        try {
            writeEvent(event)
        } catch (ex: Exception) {
            Global.log.e("Error sending audio event: $ex")
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
                        Global.log.w("Data not fully received")
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
                    Global.log.w("Payload not fully received")
                    Thread.sleep(10)
                    i++
                }
                reader.read(payloadBytes, 0, payloadLength)
                wyomingPacket.payload = payloadBytes
            }
            return wyomingPacket

        } catch (ex: Exception) {
            Global.log.e("Event read exception ${ex.toString().substring(0, ex.toString().length.coerceAtMost(50))}")
        }
        return null
    }

    private fun writeEvent(p: WyomingPacket) {
        if (p.type != "ping" && p.type != "pong" && p.type != "audio-chunk") {
            Global.log.d("Sending: ${p.toMap()}")
        }
        val eventDict: MutableMap<String, Any> = p.toMap()
        eventDict["version"] = Global.config.version

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
            Global.log.e("Error sending event: $ex")
            shutdown()
        } catch (ex: Exception) {
            Global.log.e("Unknown eError sending event: $ex")
        }

    }

    private fun shutdown() {
        running = false
        pingTimer.cancel()
        MusicPlayer.stop()
        client.close()
        Global.log.w("${client.inetAddress.hostAddress} closed the connection")
    }

}


