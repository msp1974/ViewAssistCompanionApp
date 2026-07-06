package com.msp1974.vacompanion.service

import android.content.Context
import com.msp1974.vacompanion.data.AvailableAlarms
import com.msp1974.vacompanion.data.AvailableWakeSounds
import com.msp1974.vacompanion.data.NetworkStatusManager
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.wakeword.AvailableWakeWords
import com.msp1974.vacompanion.wyoming.ServerState
import com.msp1974.vacompanion.wyoming.WyomingTCPServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

internal class BackgroundTaskController (private val context: Context, val deviceManager: DeviceManager) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var server: WyomingTCPServer? = null
    private val config = deviceManager.config
    private val deviceInfo = deviceManager.deviceInfo


    fun start() {

        server = object: WyomingTCPServer(context, deviceManager) {
            override fun onEvent(event: String, data: JsonObject) {
                Timber.d("BackgroundTask - Event: $event - $data")
            }
            override fun onState(state: ServerState, restartIfStopped: Boolean) {
                Timber.d("BackgroundTask - State: $state")
                when (state) {
                    ServerState.RUNNING -> {}
                    ServerState.STOPPED -> {
                        Timber.d("Wyoming server stopped. Restart: $restartIfStopped")
                        // Restart server
                        if (restartIfStopped) runServer()
                    }
                    ServerState.ERRORED -> {
                        restartOnError()
                    }
                    else -> {}
                }
            }
        }
        runServer()
        Timber.d("Background task initialisation completed")
    }

    fun restartOnError() {
        server?.let {
            it.stopServer()
            start()
        }
    }

    fun runServer() {
        // TODO: Implement a recovery process
        try {
            if (server != null && server?.state == ServerState.STOPPED) {
                scope.launch {
                    config.availableWakeWords = AvailableWakeWords(context).get()
                    config.availableAlarms = AvailableAlarms(context, deviceManager).get()
                    config.availableWakeSounds = AvailableWakeSounds(context, deviceManager).get()
                    server?.startServer()
                }
            } else {
                Timber.d("Server not setup or already running")
            }
        } catch (e: Exception) {
            Timber.e("Error starting server: ${e.message.toString()}")
        }
    }

    fun serverWatchDog() {
        // TODO: Add restart watchdog
    }

    fun shutdown() {
        Timber.i("Shutting down")
        server?.stopServer()
    }
}
