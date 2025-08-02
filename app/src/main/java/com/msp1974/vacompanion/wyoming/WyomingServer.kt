package com.msp1974.vacompanion.wyoming

import android.content.Context
import com.msp1974.vacompanion.utils.DeviceCapabilitiesData
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Logger
import kotlinx.serialization.json.JsonObject
import java.net.ServerSocket
import kotlin.concurrent.thread

enum class SatelliteState { STOPPED, RUNNING}
enum class PipelineStatus { INACTIVE, LISTENING, STREAMING }

interface WyomingCallback {
    fun onSatelliteStarted()
    fun onSatelliteStopped()
    fun onRequestInputAudioStream()
    fun onReleaseInputAudioStream()
}

class WyomingTCPServer (val context: Context, val port: Int, val cbCallback: WyomingCallback){
    var log = Logger()
    var runServer: Boolean = true
    var pipelineClient: ClientHandler? = null

    var deviceInfo: DeviceCapabilitiesData = DeviceCapabilitiesManager(context).getDeviceInfo()

    fun start() {
        try {
            val server = ServerSocket(port)
            log.d("Wyoming server is running on port ${server.localPort}")

            while (runServer) {
                val client = server.accept()
                // Run client in it's own thread.
                thread { ClientHandler(context, this, client).run() }
            }
        } catch (e: Exception) {
            log.e("Server exception: $e")
        }

    }

    fun stop() {
        runServer = false
    }

    fun sendAudio(audio: ByteArray) {
        if (pipelineClient != null) {
            pipelineClient?.sendAudio(audio)
        }
    }

    fun sendStatus(data: JsonObject) {
        if (pipelineClient != null) {
            pipelineClient?.sendStatus(data)
        }
    }

    fun requestInputAudioStream() {
        cbCallback.onRequestInputAudioStream()
    }

    fun releaseInputAudioStream() {
        cbCallback.onReleaseInputAudioStream()
    }

    fun satelliteStarted() {
        cbCallback.onSatelliteStarted()
    }

    fun satelliteStopped() {
        cbCallback.onSatelliteStopped()
    }

}




