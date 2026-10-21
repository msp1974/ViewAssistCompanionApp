package com.msp1974.vacompanion.service.rtsp

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.rtsp.RtspServer
import com.msp1974.vacompanion.rtsp.RtspServerState
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns the RTSP server's lifetime for as long as the background task runs - independent of
 * whether a Wyoming/HA satellite session is connected, since the camera stream is useful on its
 * own. Restarts the server when RTSP-related settings change, following the same
 * config-event-driven pattern as the rest of the app (see `APPConfig`'s `Delegates.observable`
 * properties and `config.eventBroadcaster`).
 */
class RtspServerManager(
    private val context: Context,
    private val deviceManager: DeviceManager,
) : EventListener {

    private val config = deviceManager.config
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var server: RtspServer? = null
    private var serverJob: Job? = null

    fun start() {
        config.eventBroadcaster.addListener(this)
        if (config.rtspEnabled) {
            launchServer()
        }
    }

    fun shutdown() {
        config.eventBroadcaster.removeListener(this)
        server?.stopServer()
        serverJob?.cancel()
        server = null
        config.rtspServerRunning = false
        job.cancel()
    }

    private fun launchServer() {
        serverJob?.cancel()
        val newServer = object : RtspServer(context, deviceManager) {
            override fun onState(state: RtspServerState) {
                Timber.d("RtspServerManager: state=$state")
                // Only the current server counts; a replaced one reports STOPPED after the new
                // one may already be up.
                if (server === this) config.rtspServerRunning = state == RtspServerState.RUNNING
            }

            override fun onClientCountChanged(count: Int) {
                Timber.d("RtspServerManager: clients=$count")
                config.eventBroadcaster.notifyEvent(Event("rtspClientCount", "", count))
            }
        }
        server = newServer
        serverJob = scope.launch { newServer.startServer() }
    }

    private fun restartServer() {
        Timber.i("RtspServerManager: restarting server")
        server?.stopServer()
        serverJob?.cancel()
        server = null
        config.rtspServerRunning = false
        if (config.rtspEnabled) {
            scope.launch {
                delay(300)
                launchServer()
            }
        }
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "rtspEnabled" -> {
                if (event.newValue as Boolean) {
                    if (server == null) launchServer()
                } else {
                    server?.stopServer()
                    serverJob?.cancel()
                    server = null
                    config.rtspServerRunning = false
                }
            }
            // Camera permission granted after startup (the server was skipped without it).
            "hasCameraPermission" -> {
                if (event.newValue == true && config.rtspEnabled && !config.rtspServerRunning) restartServer()
            }
            "rtspPort", "rtspResolution", "rtspFps", "rtspBitrate", "rtspKeyframeIntervalSecs",
            "rtspUsername", "rtspPassword" -> {
                if (config.rtspEnabled) restartServer()
            }
        }
    }
}
