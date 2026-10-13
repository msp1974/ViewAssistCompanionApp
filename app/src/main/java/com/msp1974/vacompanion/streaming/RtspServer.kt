package com.msp1974.vacompanion.streaming

import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.channels.ClosedChannelException
import java.util.concurrent.Executors

enum class RtspServerState { STOPPED, STARTING, RUNNING, STOPPING, ERRORED }

/**
 * Minimal single-viewer RTSP/1.0 server (RFC 2326) hand-rolled on Ktor raw sockets,
 * mirroring wyoming/WyomingTCPServer's shape (abstract class + callbacks, one
 * long-lived accept loop per port). Each accepted TCP connection becomes one
 * RtspSession; only one of them is ever allowed to become the active (PLAYing)
 * viewer at a time - see onViewerConnect/onPlay.
 */
abstract class RtspServer(private val port: Int) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var serverSocket: ServerSocket? = null
    private var runServer = true
    private var activeSession: RtspSession? = null

    var state: RtspServerState = RtspServerState.STOPPED
        private set(value) {
            field = value
            onState(value)
        }

    abstract fun onState(state: RtspServerState)

    /** Build the SDP body to answer DESCRIBE with. */
    abstract suspend fun buildSdp(): String

    /**
     * A viewer wants to PLAY. Return true to accept it as the (sole) active viewer -
     * typically this is where the camera/encoder pipeline is started - or false to
     * refuse (e.g. the camera is busy with the on-device diagnostic preview).
     */
    abstract suspend fun onViewerConnect(): Boolean

    /** The active viewer disconnected or tore down; release the camera/encoder. */
    abstract suspend fun onViewerDisconnect()

    suspend fun startServer() {
        runServer = true
        state = RtspServerState.STARTING
        val exec = Executors.newCachedThreadPool()
        val selector = ActorSelectorManager(exec.asCoroutineDispatcher())
        try {
            serverSocket = aSocket(selector).tcp().bind("0.0.0.0", port)
            Timber.i("RTSP server listening on ${serverSocket?.localAddress}")
        } catch (e: Throwable) {
            Timber.e("RtspServer: failed to bind port $port: $e")
            state = RtspServerState.ERRORED
            return
        }

        state = RtspServerState.RUNNING
        withContext(Dispatchers.IO) {
            try {
                while (runServer) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: Throwable) {
                        if (!runServer) break
                        if (e is ClosedChannelException) continue
                        throw e
                    } ?: break

                    scope.launch { handleConnection(socket) }
                }
            } catch (e: Throwable) {
                Timber.e("RtspServer: accept loop error: $e")
                state = RtspServerState.ERRORED
            } finally {
                state = RtspServerState.STOPPING
                runCatching { serverSocket?.close() }
                state = RtspServerState.STOPPED
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        lateinit var session: RtspSession
        session = object : RtspSession(socket) {
            override suspend fun onDescribe(): String = buildSdp()

            override suspend fun onPlay(): Boolean {
                if (activeSession != null && activeSession !== session) {
                    Timber.w("RtspServer: refusing PLAY - a viewer is already connected")
                    return false
                }
                if (!onViewerConnect()) return false
                activeSession = session
                return true
            }

            override suspend fun onTeardownOrDisconnect() {
                if (activeSession === session) {
                    activeSession = null
                    onViewerDisconnect()
                }
            }
        }
        session.start()
    }

    /** Forwards one RTP packet to the current active viewer, if any. */
    suspend fun sendRtpToActiveViewer(packet: ByteArray) {
        activeSession?.sendInterleavedRtp(packet)
    }

    fun stopServer() {
        runServer = false
        runCatching { serverSocket?.close() }
        activeSession?.stop()
        activeSession = null
    }
}
