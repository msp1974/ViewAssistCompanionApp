package com.msp1974.vacompanion.webrtc

import android.Manifest
import android.content.Context
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.camera.CameraAvailability
import com.msp1974.vacompanion.rtsp.RtspAuth
import com.msp1974.vacompanion.settings.APPConfig
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class WebRtcServerState { STOPPED, STARTING, RUNNING, STOPPING, ERRORED }

/**
 * A minimal WHEP (WebRTC-HTTP egress protocol) server for the shared front camera. A viewer POSTs
 * an SDP offer to `/camera/whep` and gets the SDP answer back in the `201 Created`; `DELETE` on the
 * `Location` it returns ends the session. This is what go2rtc (and so Home Assistant) takes as a
 * `webrtc:` source with `#format=whep`, and what browsers and other WHEP players can play directly.
 *
 * Shaped like [com.msp1974.vacompanion.rtsp.RtspServer]: an abstract class with callback methods,
 * instantiated anonymously at its call site (see `WebRtcServerManager`). The camera feed
 * ([WebRtcCameraSource]) starts with the first viewer and stops a short grace period after the last.
 *
 * Auth is HTTP Basic, using the same username and password as the RTSP stream, and only when both
 * are set.
 */
abstract class WebRtcServer(private val context: Context, val deviceManager: DeviceManager) {

    val config: APPConfig = deviceManager.config

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var serverSocket: ServerSocket? = null
    @Volatile private var runServer = true

    private val selectorManager = ActorSelectorManager(Executors.newCachedThreadPool().asCoroutineDispatcher())

    private var source: WebRtcCameraSource? = null
    private var sourceIdleJob: Job? = null
    private val sourceLock = Mutex()

    // Session ids that are currently live, so the count only ever includes real viewers.
    private val liveSessions = ConcurrentHashMap.newKeySet<String>()
    // The same ids, oldest first, so the oldest viewer can be replaced when there are too many.
    private val sessionOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val sessionStarted = ConcurrentHashMap<String, Long>()
    private val createLock = Mutex()

    var state: WebRtcServerState = WebRtcServerState.STOPPED
        private set(value) {
            field = value
            onState(value)
        }

    abstract fun onState(state: WebRtcServerState)
    abstract fun onClientCountChanged(count: Int)

    private val auth by lazy { RtspAuth(config.rtspUsername, config.rtspPassword) }
    private fun authRequired() = config.rtspUsername.isNotEmpty() && config.rtspPassword.isNotEmpty()

    private data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    suspend fun startServer() {
        if (!config.webrtcEnabled) {
            Timber.d("WebRtcServer: disabled, not starting")
            return
        }
        when (deviceManager.camera.currentAvailability()) {
            CameraAvailability.NO_CAMERA -> {
                Timber.d("WebRtcServer: no front camera, not starting")
                return
            }
            CameraAvailability.NO_PERMISSION -> {
                Timber.w("WebRtcServer: camera permission not granted, requesting")
                BroadcastSender.sendBroadcast(context, BroadcastSender.OPEN_PERMISSION_SCREEN, Manifest.permission.CAMERA)
                return
            }
            else -> {}
        }

        state = WebRtcServerState.STARTING
        runServer = true
        try {
            serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", config.webrtcPort)
            Timber.i("WebRTC (WHEP) server listening at ${serverSocket?.localAddress}")
        } catch (e: Throwable) {
            Timber.e("WebRtcServer: failed to bind port ${config.webrtcPort}: $e")
            state = WebRtcServerState.ERRORED
            return
        }

        state = WebRtcServerState.RUNNING
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
            Timber.e("WebRtcServer: accept loop error: $e")
            state = WebRtcServerState.ERRORED
        } finally {
            withContext(NonCancellable) {
                state = WebRtcServerState.STOPPING
                sourceIdleJob?.cancel()
                source?.stop()
                source = null
                liveSessions.clear()
                onClientCountChanged(0)
                try { serverSocket?.close() } catch (_: Exception) {}
                state = WebRtcServerState.STOPPED
            }
        }
    }

    fun stopServer() {
        runServer = false
        serverSocket?.close()
    }

    // --- HTTP ---

    /** One request per connection (`Connection: close`): WHEP signalling is a single POST. */
    private suspend fun handleConnection(socket: Socket) {
        try {
            val request = readRequest(socket) ?: return
            val write = socket.openWriteChannel(autoFlush = false)
            val response = try {
                route(request)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Timber.e("WebRtcServer: ${request.method} ${request.path} failed: $e")
                Response(500)
            }
            write.writeByteArray(response.toBytes())
            write.flush()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Timber.d("WebRtcServer: connection ended: $e")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun readRequest(socket: Socket): HttpRequest? {
        val input = socket.openReadChannel()
        val requestLine = withTimeoutOrNull(READ_TIMEOUT_MS) { input.readLine() } ?: return null
        val parts = requestLine.trim().split(" ")
        if (parts.size < 2) return null

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = withTimeoutOrNull(READ_TIMEOUT_MS) { input.readLine() } ?: return null
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        // Too big to be an SDP offer: leave the body unread; handleOffer() sees the length and refuses.
        if (length > MAX_BODY_BYTES) return HttpRequest(parts[0].uppercase(), parts[1].substringBefore('?'), headers, "")
        val body = if (length > 0) {
            val bytes = ByteArray(length)
            withTimeoutOrNull(READ_TIMEOUT_MS) { input.readFully(bytes) } ?: return null
            String(bytes, Charsets.UTF_8)
        } else ""
        return HttpRequest(parts[0].uppercase(), parts[1].substringBefore('?'), headers, body)
    }

    private class Response(val code: Int, val headers: List<String> = emptyList(), val body: String = "") {
        fun toBytes(): ByteArray {
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 $code ${reason(code)}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: POST, DELETE, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
                append("Access-Control-Expose-Headers: Location\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            return head.toByteArray(Charsets.UTF_8) + bodyBytes
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            else -> if (code >= 500) "Server Error" else "Error"
        }
    }

    private suspend fun route(request: HttpRequest): Response {
        Timber.d("WHEP <- ${request.method} ${request.path}")
        if (request.method == "OPTIONS") {
            return Response(204, listOf("Accept-Post: application/sdp"))
        }
        if (request.path != WHEP_PATH && !request.path.startsWith("$WHEP_PATH/")) return Response(404)

        if (authRequired() && !auth.validate(request.headers["authorization"], request.method, "")) {
            return Response(401, listOf("WWW-Authenticate: Basic realm=\"VACA\""))
        }

        return when {
            request.method == "POST" && request.path == WHEP_PATH -> handleOffer(request)
            request.method == "DELETE" && request.path.startsWith("$WHEP_PATH/") -> {
                endSession(request.path.removePrefix("$WHEP_PATH/"))
                Response(200)
            }
            else -> Response(405)
        }
    }

    private suspend fun handleOffer(request: HttpRequest): Response {
        if ((request.headers["content-length"]?.toIntOrNull() ?: 0) > MAX_BODY_BYTES) return Response(413)
        if (request.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/sdp") {
            return Response(415)
        }
        if (!request.body.startsWith("v=")) return Response(400)

        val camera = deviceManager.camera.currentAvailability()
        if (camera != CameraAvailability.READY) {
            Timber.w("WebRtcServer: camera is $camera, refusing the offer")
            return Response(503, listOf("Retry-After: 5"))
        }

        // Sessions are made one at a time so the limit is exact: clients that all retry together
        // (Home Assistant's cards do, the moment the server comes back) would otherwise each pass
        // the check before any had been counted.
        val (session, answer) = createLock.withLock {
            if (sessionOrder.size >= MAX_SESSIONS) {
                // Cards that retry leave dead connections behind, so an old viewer is replaced - but
                // not a young one, or two rival clients would take each other's place for ever.
                val oldest = sessionOrder.peek()
                val age = System.currentTimeMillis() - (sessionStarted[oldest] ?: 0L)
                if (age < MIN_SESSION_AGE_MS) {
                    Timber.w("WebRtcServer: $MAX_SESSIONS viewers already, refusing another for now")
                    return Response(503, listOf("Retry-After: 5"))
                }
                sessionOrder.poll()
                Timber.w("WebRtcServer: $MAX_SESSIONS viewers already, closing the oldest ($oldest)")
                endSession(oldest)
            }

            try {
                val created = ensureSourceRunning().createSession(request.body)
                liveSessions.add(created.first.id)
                sessionOrder.add(created.first.id)
                sessionStarted[created.first.id] = System.currentTimeMillis()
                created
            } catch (e: Exception) {
                Timber.e("WebRtcServer: couldn't answer the offer: $e")
                onSessionsChanged()
                return Response(400)
            }
        }
        onClientCountChanged(liveSessions.size)

        // A viewer that never gets as far as connecting would otherwise be counted for ever.
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (session.id in liveSessions && source?.isConnected(session.id) != true) {
                endSession(session.id)
            }
        }

        return Response(
            201,
            listOf("Content-Type: application/sdp", "Location: $WHEP_PATH/${session.id}"),
            answer
        )
    }

    private suspend fun ensureSourceRunning(): WebRtcCameraSource = sourceLock.withLock {
        sourceIdleJob?.cancel()
        sourceIdleJob = null
        val current = source
        if (current != null && current.isRunning) {
            current
        } else {
            WebRtcCameraSource(context, deviceManager.camera, config).also {
                // Called on libwebrtc's own thread, from inside the peer's callback: the peer can't be
                // closed and disposed from there, so hand it to another thread.
                it.onSessionEnded = { id -> scope.launch { endSession(id) } }
                it.start()
                source = it
            }
        }
    }

    private fun endSession(id: String) {
        source?.closeSession(id)
        sessionOrder.remove(id)
        sessionStarted.remove(id)
        if (liveSessions.remove(id)) onClientCountChanged(liveSessions.size)
        onSessionsChanged()
    }

    /** Stops the camera feed once nothing is watching it. */
    private fun onSessionsChanged() {
        if (liveSessions.isNotEmpty()) return
        sourceIdleJob?.cancel()
        sourceIdleJob = scope.launch {
            delay(SOURCE_IDLE_GRACE_MS)
            sourceLock.withLock {
                if (liveSessions.isEmpty()) {
                    source?.stop()
                    source = null
                    Timber.i("WebRtcServer: camera feed stopped (idle)")
                }
            }
        }
    }

    companion object {
        const val WHEP_PATH = "/camera/whep"
        private const val READ_TIMEOUT_MS = 10_000L
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val MAX_SESSIONS = 8
        private const val MIN_SESSION_AGE_MS = 10_000L
        private const val SOURCE_IDLE_GRACE_MS = 10_000L
    }
}
