package com.msp1974.vacompanion.rtsp

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.camera.CameraAvailability
import com.msp1974.vacompanion.settings.APPConfig
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

enum class RtspServerState { STOPPED, STARTING, RUNNING, STOPPING, ERRORED }

/**
 * A minimal RTSP server streaming the shared front camera as H.264/RTP. Mirrors
 * [com.msp1974.vacompanion.wyoming.WyomingTCPServer]'s shape: an abstract class with callback
 * methods, instantiated anonymously at its call site (see `RtspServerManager`).
 *
 * The H.264 encoder is started on-demand (first DESCRIBE/PLAY) and stopped again a short grace
 * period after the last session stops playing, so an idle server does not hold the shared camera
 * at streaming resolution.
 */
abstract class RtspServer(private val context: Context, val deviceManager: DeviceManager) {

    val config: APPConfig = deviceManager.config

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var serverSocket: ServerSocket? = null
    @Volatile private var runServer = true
    private val sessions = CopyOnWriteArrayList<RtspSession>()

    private val selectorManager = ActorSelectorManager(Executors.newCachedThreadPool().asCoroutineDispatcher())

    private var encoder: H264Encoder? = null
    private var encoderIdleJob: Job? = null
    private val encoderLock = Mutex()

    var state: RtspServerState = RtspServerState.STOPPED
        private set(value) {
            field = value
            onState(value)
        }

    abstract fun onState(state: RtspServerState)
    abstract fun onClientCountChanged(count: Int)

    fun authRequired(): Boolean = config.rtspUsername.isNotEmpty() && config.rtspPassword.isNotEmpty()

    fun authChallengeHeaders(nonce: String): List<String> {
        return RtspAuth(config.rtspUsername, config.rtspPassword).challengeHeaders(nonce)
            .map { "WWW-Authenticate: $it" }
    }

    fun authValidate(header: String?, method: String, nonce: String): Boolean {
        return RtspAuth(config.rtspUsername, config.rtspPassword).validate(header, method, nonce)
    }

    fun cameraAvailability(): CameraAvailability = deviceManager.camera.currentAvailability()

    suspend fun ensureEncoderRunning(): H264Encoder {
        return encoderLock.withLock {
            encoderIdleJob?.cancel()
            encoderIdleJob = null
            val current = encoder
            if (current != null && current.isRunning()) {
                current
            } else {
                val newEncoder = H264Encoder(deviceManager.camera, config)
                newEncoder.start()
                encoder = newEncoder
                newEncoder
            }
        }
    }

    /** Called whenever a session's playing state may have changed (pause, teardown, disconnect)
     *  so the encoder can be torn down once nothing is actually playing. */
    fun onSessionIdleChanged() {
        if (sessions.any { it.isPlaying }) return
        encoderIdleJob?.cancel()
        encoderIdleJob = scope.launch {
            delay(ENCODER_IDLE_GRACE_MS)
            encoderLock.withLock {
                if (sessions.none { it.isPlaying }) {
                    encoder?.stop()
                    encoder = null
                    Timber.i("RtspServer: encoder stopped (idle)")
                }
            }
        }
    }

    fun onSessionClosed(session: RtspSession) {
        sessions.remove(session)
        onClientCountChanged(sessions.size)
        onSessionIdleChanged()
    }

    suspend fun startServer() {
        if (!config.rtspEnabled) {
            Timber.d("RtspServer: disabled, not starting")
            return
        }
        if (deviceManager.camera.currentAvailability() == CameraAvailability.NO_CAMERA) {
            Timber.d("RtspServer: no front camera, not starting")
            return
        }

        state = RtspServerState.STARTING
        runServer = true
        try {
            serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", config.rtspPort)
            Timber.i("RTSP server listening at ${serverSocket?.localAddress}")
        } catch (e: Throwable) {
            Timber.e("RtspServer: failed to bind port ${config.rtspPort}: $e")
            state = RtspServerState.ERRORED
            return
        }

        state = RtspServerState.RUNNING
        try {
            while (runServer) {
                val socket = try {
                    serverSocket?.accept()
                } catch (e: Throwable) {
                    if (!runServer) break
                    if (e is ClosedChannelException) continue
                    throw e
                } ?: break

                scope.launch {
                    val session = RtspSession(scope, socket, selectorManager, this@RtspServer)
                    sessions.add(session)
                    onClientCountChanged(sessions.size)
                    session.run()
                }
            }
        } catch (e: Throwable) {
            Timber.e("RtspServer: accept loop error: $e")
            state = RtspServerState.ERRORED
        } finally {
            withContext(NonCancellable) {
                state = RtspServerState.STOPPING
                sessions.forEach { it.close() }
                sessions.clear()
                onClientCountChanged(0)
                encoderIdleJob?.cancel()
                encoder?.stop()
                encoder = null
                try { serverSocket?.close() } catch (_: Exception) {}
                state = RtspServerState.STOPPED
            }
        }
    }

    fun stopServer() {
        runServer = false
        serverSocket?.close()
    }

    companion object {
        private const val ENCODER_IDLE_GRACE_MS = 10_000L
    }
}
