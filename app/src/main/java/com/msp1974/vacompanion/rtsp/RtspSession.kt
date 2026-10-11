package com.msp1974.vacompanion.rtsp

import com.msp1974.vacompanion.device.camera.CameraAvailability
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import timber.log.Timber
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private data class RtspRequest(
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
)

/**
 * Handles one RTSP client connection: request parsing, the OPTIONS/DESCRIBE/SETUP/PLAY/
 * PAUSE/TEARDOWN state machine, and streaming RTP packets for the "/camera" track either
 * TCP-interleaved on this same socket or over a dedicated UDP pair.
 */
class RtspSession(
    private val scope: CoroutineScope,
    private val socket: Socket,
    private val selectorManager: SelectorManager,
    private val server: RtspServer,
) {
    private val receiveChannel = socket.openReadChannel()
    private val sendChannel = socket.openWriteChannel(autoFlush = false)
    private val writeMutex = Mutex()

    private val sessionId = Random.nextInt(100000, 999999).toString()
    @Volatile private var running = true
    private val authNonce = RtspAuth.generateNonce()
    private var authenticated = false

    private enum class Transport { NONE, TCP_INTERLEAVED, UDP }
    private var transport = Transport.NONE
    private var rtpChannel = 0
    private var rtcpChannel = 1
    private var udpSocket: BoundDatagramSocket? = null
    private var clientAddress: InetSocketAddress? = null

    @Volatile var isPlaying = false
        private set

    private var streamJob: Job? = null
    private var rtcpJob: Job? = null
    private val packetizer = RtpH264Packetizer()

    private val clientIp: String by lazy {
        try { socket.remoteAddress.toString().split(":")[0].replace("/", "") } catch (_: Exception) { "" }
    }

    suspend fun run() {
        try {
            while (running) {
                val request = readRequest() ?: break
                handleRequest(request)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Timber.d("RtspSession: connection from $clientIp ended: $e")
        } finally {
            close()
        }
    }

    private suspend fun readRequest(): RtspRequest? {
        var requestLine: String?
        do {
            requestLine = withTimeoutOrNull(SESSION_TIMEOUT) { receiveChannel.readLine() } ?: return null
        } while (requestLine.isBlank())

        val parts = requestLine.trim().split(" ")
        if (parts.size < 3) return null
        val method = parts[0]
        val uri = parts[1]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = withTimeoutOrNull(SESSION_TIMEOUT) { receiveChannel.readLine() } ?: return null
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            // Bodies are not expected for the methods this server supports - drain and ignore.
            receiveChannel.readFully(ByteArray(contentLength))
        }

        return RtspRequest(method, uri, headers)
    }

    private suspend fun handleRequest(request: RtspRequest) {
        Timber.d("RTSP <- ${request.method} ${request.uri}")
        val cseq = request.headers["cseq"] ?: "0"

        if (!isCameraPath(request.uri)) {
            respond(404, cseq)
            return
        }

        if (server.authRequired() && !authenticated) {
            if (!server.authValidate(request.headers["authorization"], request.method, authNonce)) {
                respond(401, cseq, extraHeaders = server.authChallengeHeaders(authNonce))
                return
            }
            authenticated = true
        }

        when (request.method.uppercase()) {
            "OPTIONS" -> respond(200, cseq, extraHeaders = listOf(
                "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER"
            ))
            "DESCRIBE" -> handleDescribe(cseq)
            "SETUP" -> handleSetup(request, cseq)
            "PLAY" -> handlePlay(cseq)
            "PAUSE" -> handlePause(cseq)
            "GET_PARAMETER" -> respond(200, cseq, extraHeaders = listOf("Session: $sessionId"))
            "TEARDOWN" -> {
                respond(200, cseq, extraHeaders = listOf("Session: $sessionId"))
                running = false
            }
            else -> respond(501, cseq)
        }
    }

    private fun isCameraPath(uri: String): Boolean {
        val path = try {
            val afterScheme = uri.substringAfter("://", uri)
            "/" + afterScheme.substringAfter("/", "")
        } catch (e: Exception) {
            uri
        }
        val trimmed = path.trimEnd('/')
        return trimmed == "/camera" || trimmed.startsWith("/camera/")
    }

    private suspend fun handleDescribe(cseq: String) {
        if (server.cameraAvailability() != CameraAvailability.READY) {
            respond(503, cseq)
            return
        }

        val encoder = server.ensureEncoderRunning()
        val spsPpsBlob = encoder.awaitConfig()
        if (spsPpsBlob == null) {
            respond(503, cseq)
            return
        }
        val (sps, pps) = NalUtils.extractSpsPps(spsPpsBlob)
        if (sps == null || pps == null) {
            respond(503, cseq)
            return
        }

        val spropParams = "${b64(sps)},${b64(pps)}"
        val profileLevelId = sps.copyOfRange(1, 4).joinToString("") { "%02x".format(it) }

        val sdp = buildString {
            append("v=0\r\n")
            append("o=- 0 0 IN IP4 0.0.0.0\r\n")
            append("s=VACA Camera\r\n")
            append("t=0 0\r\n")
            append("m=video 0 RTP/AVP ${RtpH264Packetizer.PAYLOAD_TYPE}\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=rtpmap:${RtpH264Packetizer.PAYLOAD_TYPE} H264/${RtpH264Packetizer.CLOCK_RATE}\r\n")
            append("a=fmtp:${RtpH264Packetizer.PAYLOAD_TYPE} packetization-mode=1;profile-level-id=$profileLevelId;sprop-parameter-sets=$spropParams\r\n")
            append("a=control:trackID=0\r\n")
        }

        respond(200, cseq, body = sdp, contentType = "application/sdp")
    }

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private suspend fun handleSetup(request: RtspRequest, cseq: String) {
        val transportHeader = request.headers["transport"] ?: ""

        if (transportHeader.contains("interleaved=", ignoreCase = true)) {
            val range = Regex("interleaved=(\\d+)-(\\d+)").find(transportHeader)
            rtpChannel = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
            rtcpChannel = range?.groupValues?.get(2)?.toIntOrNull() ?: (rtpChannel + 1)
            transport = Transport.TCP_INTERLEAVED
            respond(200, cseq, extraHeaders = listOf(
                "Transport: RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel",
                "Session: $sessionId"
            ))
        } else if (transportHeader.contains("client_port=", ignoreCase = true)) {
            val ports = Regex("client_port=(\\d+)-(\\d+)").find(transportHeader)
            val clientRtpPort = ports?.groupValues?.get(1)?.toIntOrNull()
            if (clientRtpPort == null) {
                respond(461, cseq)
                return
            }
            val serverRtpPort = try {
                val udp = aSocket(selectorManager).udp().bind()
                udpSocket = udp
                clientAddress = InetSocketAddress(clientIp, clientRtpPort)
                (udp.localAddress as InetSocketAddress).port
            } catch (e: Exception) {
                Timber.e("RtspSession: failed to open UDP socket: $e")
                respond(500, cseq)
                return
            }
            transport = Transport.UDP
            respond(200, cseq, extraHeaders = listOf(
                "Transport: RTP/AVP;unicast;client_port=$clientRtpPort-${clientRtpPort + 1};server_port=$serverRtpPort-${serverRtpPort + 1}",
                "Session: $sessionId"
            ))
        } else {
            respond(461, cseq)
        }
    }

    private suspend fun handlePlay(cseq: String) {
        if (transport == Transport.NONE) {
            respond(455, cseq)
            return
        }
        if (server.cameraAvailability() != CameraAvailability.READY) {
            respond(503, cseq)
            return
        }

        val encoder = server.ensureEncoderRunning()
        encoder.requestKeyFrame()
        isPlaying = true
        respond(200, cseq, extraHeaders = listOf("Session: $sessionId", "Range: npt=0.000-"))

        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                encoder.frames.collect { frame -> sendFrame(frame) }
            } catch (e: CancellationException) {
                // Expected on pause/teardown.
            } catch (e: Exception) {
                Timber.w("RtspSession: streaming error: $e")
            }
        }

        if (rtcpJob == null) {
            rtcpJob = scope.launch { sendRtcpPeriodically() }
        }
    }

    private suspend fun handlePause(cseq: String) {
        isPlaying = false
        streamJob?.cancel()
        streamJob = null
        respond(200, cseq, extraHeaders = listOf("Session: $sessionId"))
        server.onSessionIdleChanged()
    }

    private suspend fun sendFrame(frame: EncodedFrame) {
        val packets = packetizer.packetizeAccessUnit(frame.nals, frame.ptsUs)
        for (packet in packets) {
            when (transport) {
                Transport.TCP_INTERLEAVED -> sendInterleaved(rtpChannel, packet)
                Transport.UDP -> sendUdp(packet, isRtcp = false)
                Transport.NONE -> {}
            }
        }
    }

    private suspend fun sendRtcpPeriodically() {
        while (running) {
            kotlinx.coroutines.delay(5.seconds)
            if (!isPlaying) continue
            try {
                val now = System.currentTimeMillis()
                val ntpSeconds = now / 1000 + NTP_EPOCH_OFFSET_SECS
                val ntpFraction = ((now % 1000) * 4294967296L) / 1000L
                val sr = packetizer.buildSenderReport(ntpSeconds, ntpFraction, packetizer.toRtpTimestamp(now * 1000))
                when (transport) {
                    Transport.TCP_INTERLEAVED -> sendInterleaved(rtcpChannel, sr)
                    Transport.UDP -> sendUdp(sr, isRtcp = true)
                    Transport.NONE -> {}
                }
            } catch (e: Exception) {
                Timber.w("RtspSession: RTCP send failed: $e")
            }
        }
    }

    private suspend fun sendInterleaved(channel: Int, payload: ByteArray) {
        val framed = ByteArray(4 + payload.size)
        framed[0] = '$'.code.toByte()
        framed[1] = channel.toByte()
        framed[2] = ((payload.size ushr 8) and 0xFF).toByte()
        framed[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, framed, 4, payload.size)
        writeRaw(framed)
    }

    private suspend fun sendUdp(payload: ByteArray, isRtcp: Boolean) {
        val udp = udpSocket ?: return
        val target = clientAddress ?: return
        val port = if (isRtcp) target.port + 1 else target.port
        try {
            val source = Buffer().apply { write(payload) }
            udp.send(Datagram(source, InetSocketAddress(target.hostname, port)))
        } catch (e: Exception) {
            Timber.w("RtspSession: UDP send failed: $e")
        }
    }

    private suspend fun respond(
        code: Int,
        cseq: String,
        extraHeaders: List<String> = emptyList(),
        body: String = "",
        contentType: String = "",
    ) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 $code ${reasonPhrase(code)}\r\n")
        sb.append("CSeq: $cseq\r\n")
        sb.append("Server: VACA\r\n")
        extraHeaders.forEach { sb.append("$it\r\n") }
        if (body.isNotEmpty()) {
            sb.append("Content-Type: $contentType\r\n")
            sb.append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        }
        sb.append("\r\n")
        sb.append(body)
        writeRaw(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private suspend fun writeRaw(bytes: ByteArray) {
        writeMutex.withLock {
            try {
                sendChannel.writeByteArray(bytes)
                sendChannel.flush()
            } catch (e: Exception) {
                Timber.w("RtspSession: write failed: $e")
                running = false
            }
        }
    }

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        401 -> "Unauthorized"
        404 -> "Not Found"
        455 -> "Method Not Valid in This State"
        461 -> "Unsupported Transport"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        else -> "Unknown"
    }

    fun close() {
        if (!running && streamJob == null && udpSocket == null) return
        running = false
        isPlaying = false
        streamJob?.cancel()
        rtcpJob?.cancel()
        try { udpSocket?.close() } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
        server.onSessionClosed(this)
    }

    companion object {
        private val SESSION_TIMEOUT = 60.seconds
        private const val NTP_EPOCH_OFFSET_SECS = 2208988800L
    }
}
