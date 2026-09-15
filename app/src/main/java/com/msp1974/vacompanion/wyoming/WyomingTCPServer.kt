package com.msp1974.vacompanion.wyoming

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.WyomingServerStatus
import com.msp1974.vacompanion.satellite.Satellite
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.port
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.nio.channels.ClosedChannelException
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface IEvents {
    fun onEvent(event: String, data: JsonObject)
    fun onState(state: ServerState, restartIfStopped: Boolean = true)
}

data class Connection(
    val id: String,
    val handler: WyomingClientHandler,
)

abstract class WyomingTCPServer(private val context: Context, val deviceManager: DeviceManager): IEvents {

    val config = deviceManager.config
    val deviceInfo = deviceManager.deviceInfo

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var satelliteStatusListenerJob: Job? = null

    private var runServer: Boolean = true
    var satellite: Satellite? = null
    private val clients = mutableMapOf<String, Connection>()
    private val zeroconf = Zeroconf(context, config.uuid)
    private var serverSocket: ServerSocket? = null
    private var restartIfStopped: Boolean = false

    private val infoBuilder: WyomingInfoBuilder = WyomingInfoBuilder(deviceManager)
    private val capabilitiesBuilder: WyomingCapabilitiesBuilder = WyomingCapabilitiesBuilder(deviceManager)

    var state: ServerState = ServerState.STOPPED
        set(value) {
            field = value
            updateStatus()
            onState(value, restartIfStopped)
        }

    suspend fun startServer() {
        val exec = Executors.newCachedThreadPool()
        val selector = ActorSelectorManager(exec.asCoroutineDispatcher())

        state = ServerState.STARTING
        Timber.d("Wyoming TCP Server starting")


        try {
            serverSocket =
                aSocket(selector).tcp().bind("0.0.0.0", config.serverPort)
            Timber.d("Wyoming TCP Server started and listening at ${serverSocket?.localAddress}")
        } catch (e: Throwable) {
            Timber.e("Server Error: ${e.toString()}")
            return
        }


        Timber.d("Starting zeroconf")
        try {
            registerNSD()
        } catch (e: Exception) {
            Timber.e("Error starting zeroconf: $e")
            state = ServerState.ERRORED
            return
        }

        withContext(Dispatchers.IO) {
            try {
                state = ServerState.RUNNING
                restartIfStopped = true
                while (runServer) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: Throwable) {
                        if (!runServer && e is ClosedChannelException) {
                            Timber.d("Server socket closed while stopping; exiting accept loop")
                            break
                        }
                        if (e is ClosedChannelException) {
                            Timber.w("Accept loop saw closed channel while running; continuing")
                            continue
                        }
                        throw e
                    }

                    if (socket == null) {
                        if (runServer) {
                            Timber.w("Accept returned null socket while runServer=true")
                            continue
                        }
                        break
                    }

                    // Setting up a connection must never be able to stop the server.
                    // A peer that connects and immediately disconnects (a TCP health
                    // check, a port scan, `nc -z`) can make the setup below throw
                    // ClosedChannelException. That used to escape to the outer
                    // `catch (e: Throwable)`, which runs the `finally` block and shuts
                    // down the whole Wyoming server - taking the satellite and any
                    // in-progress media playback with it. Contain per-client failures
                    // here so one transient peer cannot take everything down.
                    try {
                        val remoteAddress = runCatching { socket.remoteAddress.toString() }
                            .getOrElse {
                                "unknown-remote(${it::class.simpleName})"
                            }
                        val id = runCatching { socket.remoteAddress.port().toString() }
                            .getOrElse {
                                "unknown-${System.nanoTime()}"
                            }

                        val data = buildJsonObject {
                            put("remoteId", remoteAddress)
                        }

                        val client: WyomingClientHandler = object : WyomingClientHandler(scope, socket) {
                            override suspend fun onClientDisconnected(clientId: String) {

                                if (clientId in clients) {
                                    val client = clients[clientId]
                                    client?.handler?.stop()
                                    clients.remove(clientId)
                                }
                                Timber.d("Client disconnected: $clientId.  Total: ${clients.size}")

                                if (clientId == satellite?.clientId) {
                                    satellite?.clientId = ""
                                }

                                if (clients.isEmpty()) {
                                    Timber.d("No clients connected")
                                    satellite?.clientId = ""
                                    disconnectionMonitor()
                                }
                                updateStatus()
                            }

                            override suspend fun onWyomingMessage(
                                clientId: String,
                                message: WyomingPacket
                            ) {
                                try {
                                    // Added to prevent processing first message before client handler correctly setup
                                    withTimeout(250.milliseconds) {
                                        while (clientId !in clients) {
                                            delay(10.milliseconds)
                                        }
                                    }
                                    messageHandler(clientId, message)
                                } catch (e: Exception) {
                                    Timber.e("Error processing message: $e")
                                }
                            }
                        }

                            clients[id] = Connection(id, client)
                            Timber.d("Client connected: $remoteAddress.  Total: ${clients.size}")
                        updateStatus()
                            onEvent("client_connected", data)
                    } catch (e: ClosedChannelException) {
                        Timber.w("Client socket closed during connection setup; skipping")
                        runCatching { socket.close() }
                        continue
                    } catch (e: Throwable) {
                        Timber.e(e, "Client setup failed; continuing")
                        runCatching { socket.close() }
                        continue
                    }
                }
            } catch (e: Throwable) {
                ensureActive()
                Timber.e("Server Error: ${e.toString()}")
            } finally {
                withContext(NonCancellable) {
                    Timber.d("Stopping Wyoming TCP Server...")
                    state = ServerState.STOPPING
                    stopSatellite()
                    try {
                        if (!clients.isEmpty()) {
                            clients.forEach { client ->
                                Timber.d("Stopping client: ${client.key}")
                                client.value.handler.stop()
                            }
                            clients.clear()
                        }
                        serverSocket?.close()

                    } catch (e: Exception) {
                        Timber.e("Error when stopping server: $e")
                    }
                    unregisterNSD()
                    state = ServerState.STOPPED
                    Timber.i("Wyoming TCP Server stopped")
                }
            }
        }
    }

    fun disconnectionMonitor() {
        val timeout = DISCONNECTION_TIMEOUT_MINS
        val startTime = System.currentTimeMillis()
        scope.launch {
            // Stop satellite if connection lost for more than timeout
            while (clients.isEmpty()) {
                delay(1.seconds)
                val currentTime = System.currentTimeMillis()
                if (startTime + (timeout * 60 * 1000) < currentTime) {
                    Timber.w("Terminating Satellite due to network disconnection timeout after ${timeout}mins")
                    stopSatellite()
                    break
                }
            }
        }
    }

    fun stopServer(restartAfterStop: Boolean = false) {
        restartIfStopped = restartAfterStop
        runServer = false
        serverSocket?.close()
    }

    private fun registerNSD() {
        zeroconf.registerService(config.serverPort)
    }

    private fun unregisterNSD() {
        zeroconf.unregisterService()
    }

    private suspend fun messageHandler(clientId: String, packet: WyomingPacket) {
        if (packet.type !in IGNORED_LOG_EVENTS) {
            Timber.d("Received <- ${clientId}: ${packet.toMap()}")
        }

        try {
            when (packet.type) {
                WyomingEvent.PING -> sendPong(clientId)
                WyomingEvent.PONG -> {}
                WyomingEvent.DESCRIBE -> sendInfo(clientId)
                WyomingEvent.CAPABILITIES -> sendCapabilities(clientId)
                WyomingEvent.RUN_SATELLITE -> {
                    startSatellite(clientId)
                }
                WyomingEvent.PAUSE_SATELLITE -> stopSatellite()
                else -> {
                    var retryCount = 2
                    var processed = false
                    while (retryCount > 0) {
                        if (satellite != null && satellite?.state != SatelliteState.STOPPED) {
                            satellite?.processMessage(packet)
                            processed = true
                            break
                        } else {
                            retryCount--
                            delay(1.seconds)
                        }
                    }
                    if (!processed) {
                        Timber.w("Cannot process message ${packet.toMap()}")
                        when {
                            satellite == null -> Timber.w("Satellite is null")
                            clientId != satellite?.clientId -> Timber.w("Client id does not match satellite id")
                            satellite?.state == SatelliteState.STOPPED -> Timber.w("Satellite is stopped")
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Timber.e("Error processing event ${packet.type}: $ex")
        }
    }

    fun sendPong(clientId: String) {
        respondToGenericMessage(clientId, "pong", buildJsonObject { put("text", "") })
    }

    fun sendInfo(clientId: String) {
        respondToGenericMessage(clientId, "info", infoBuilder.buildInfo())
    }

    fun sendCapabilities(clientId: String) {
        respondToGenericMessage(clientId, "capabilities", capabilitiesBuilder.buildInfo())
    }

    private suspend fun startSatellite(clientId: String) {
        Timber.d("Processing run satellite")

        if (config.settingsOpen) {
            Timber.d("Settings screen open - delaying satellite start")
            while (config.settingsOpen) {
                delay(500.milliseconds)
                clients[clientId]?.handler?.lastMessage = System.currentTimeMillis()
            }
            Timber.d("Settings screen closed - proceeding with satellite start")
        }

        val serverIP = clients[clientId]?.handler?.clientIP ?: ""

        if (config.pairedDeviceID.isEmpty()) {
            config.pairedDeviceID = serverIP
        } else if (!isValidServer(serverIP)) {
            Timber.e("Non paired server attempted to start satellite.  Paired to ${config.pairedDeviceID}, attempting server: $serverIP")
            return
        }

        if (satellite != null) {
            if (satellite?.state == SatelliteState.RUNNING) {
                Timber.d("Satellite already running - updating clientId")
                scope.launch {
                    satellite?.handleSatelliteTakeover(clientId)
                }
                return
            } else if (satellite?.state == SatelliteState.STOPPING) {
                try {
                    Timber.d("Satellite shutting down.  Waiting...")
                    withTimeout(2.seconds) {
                        while (satellite != null) {
                            delay(100.milliseconds)
                        }
                    }
                } catch (_: Exception) {
                    Timber.w("Satellite taking too long to stop.  Terminating")
                    stopSatellite()
                }
            } else {
                Timber.d("Satellite in a bad state - stopping and starting again")
                stopSatellite()
            }
        }
        try {
            Timber.d("Starting satellite")
            config.homeAssistantConnectedIP = serverIP
            satellite = object: Satellite(context, deviceManager, scope, clientId) {
                override fun onEvent(event: String, data: JsonObject) {
                    Timber.d("Satellite event: $event")
                }

                override fun sendSatelliteMessage(
                    clientId: String,
                    type: String,
                    data: JsonObject,
                    payload: ByteArray
                ) {
                    sendMessage(clientId, type, data, payload)
                }
            }.also { newSatellite ->
                unregisterNSD()
                scope.launch { newSatellite.start() }
                if (satelliteStatusListenerJob != null && satelliteStatusListenerJob!!.isActive) {
                    satelliteStatusListenerJob!!.cancel()
                }
                // Collect directly off newSatellite (not the `satellite` field) - the field
                // assignment below only lands once this whole `.also` block returns, so a
                // field read here could race it and (rarely) observe the previous satellite
                // or null, leaving WyomingServerStatus.satelliteRunning stuck stale.
                satelliteStatusListenerJob = scope.launch {
                    newSatellite.satelliteState.collect { _ ->
                        updateStatus()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e("Error starting satellite: $e")
        }
    }

    private fun isValidServer(ipAddr: String): Boolean {
        return config.pairedDeviceID == "" || config.pairedDeviceID == ipAddr
    }

    private suspend fun stopSatellite() {
        if (satellite != null) {
            val satId = satellite?.clientId
            satellite?.stop()
            satellite = null
            withContext(Dispatchers.IO) {
                clients[satId]?.handler?.socket?.close()
                clients.remove(satId)
            }
            registerNSD()
            satelliteStatusListenerJob?.cancel()
        }
    }

    private fun respondToGenericMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray = ByteArray(0)) {
        val packet = WyomingPacket(type, data, payload)
        if (type !in IGNORED_LOG_EVENTS) {
            Timber.d("Sending -> $clientId: ${packet.toMap()}")
        }
        clients[clientId]?.handler?.writeMessage(packet)
    }

    fun sendMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray = ByteArray(0)) {
        val packet = WyomingPacket(type, data, payload)
        if (satellite != null && clientId != ""   && clientId == satellite?.clientId) {
            if (type !in IGNORED_LOG_EVENTS) {
                Timber.d("Sending -> $clientId: ${packet.toMap()}")
            }
            clients[clientId]?.handler?.writeMessage(packet)
        } else {
            Timber.w("Failed sending -> $clientId: ${packet.toMap()}. No connection with that client id")
        }
    }

    private fun updateStatus() {
        deviceManager.updateServerState(
            WyomingServerStatus(
                state = state,
                connected = clients.isNotEmpty(),
                satelliteRunning = satellite?.state == SatelliteState.RUNNING
            )
        )
        Timber.d("Server status: ${deviceManager.status.value.wyoming}")
    }

    companion object {
        private val IGNORED_LOG_EVENTS = setOf("ping", "pong", "audio-chunk")
        private const val DISCONNECTION_TIMEOUT_MINS = 5
    }
}
