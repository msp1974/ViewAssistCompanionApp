package com.msp1974.vacompanion.service.webrtc

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.webrtc.WebRtcServer
import com.msp1974.vacompanion.webrtc.WebRtcServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns the WebRTC (WHEP) server's lifetime for as long as the background task runs, the same way
 * [com.msp1974.vacompanion.service.rtsp.RtspServerManager] does for RTSP: independent of any
 * Wyoming/HA satellite session, and restarted when a setting it depends on changes. The stream
 * settings (resolution, fps, bitrate, credentials) are the RTSP ones, shared.
 */
class WebRtcServerManager(
    private val context: Context,
    private val deviceManager: DeviceManager,
) : EventListener {

    private val config = deviceManager.config
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var server: WebRtcServer? = null
    private var serverJob: Job? = null

    fun start() {
        config.eventBroadcaster.addListener(this)
        if (config.webrtcEnabled) launchServer()
    }

    fun shutdown() {
        config.eventBroadcaster.removeListener(this)
        server?.stopServer()
        serverJob?.cancel()
        server = null
        config.webrtcServerRunning = false
        job.cancel()
    }

    private fun launchServer() {
        serverJob?.cancel()
        val newServer = object : WebRtcServer(context, deviceManager) {
            override fun onState(state: WebRtcServerState) {
                Timber.d("WebRtcServerManager: state=$state")
                // Only the current server counts; a replaced one reports STOPPED after the new
                // one may already be up.
                if (server === this) config.webrtcServerRunning = state == WebRtcServerState.RUNNING
            }

            override fun onClientCountChanged(count: Int) {
                Timber.d("WebRtcServerManager: clients=$count")
                config.eventBroadcaster.notifyEvent(Event("webrtcClientCount", "", count))
            }
        }
        server = newServer
        serverJob = scope.launch { newServer.startServer() }
    }

    private fun stopCurrent() {
        server?.stopServer()
        serverJob?.cancel()
        server = null
        config.webrtcServerRunning = false
    }

    private fun restartServer() {
        Timber.i("WebRtcServerManager: restarting server")
        stopCurrent()
        if (config.webrtcEnabled) {
            scope.launch {
                delay(300)
                launchServer()
            }
        }
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "webrtcEnabled" -> {
                if (event.newValue as Boolean) {
                    if (server == null) launchServer()
                } else {
                    stopCurrent()
                }
            }
            // Camera permission granted after startup (the server was skipped without it).
            "hasCameraPermission" -> {
                if (event.newValue == true && config.webrtcEnabled && !config.webrtcServerRunning) restartServer()
            }
            "webrtcPort", "rtspResolution", "rtspFps", "rtspBitrate", "rtspUsername", "rtspPassword" -> {
                if (config.webrtcEnabled) restartServer()
            }
        }
    }
}
