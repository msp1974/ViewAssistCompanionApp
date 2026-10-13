package com.msp1974.vacompanion.streaming

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readLine
import io.ktor.utils.io.readPacket
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.random.Random

data class RtspRequest(
    val method: String,
    val uri: String,
    val cSeq: String,
    val headers: Map<String, String>,
)

/**
 * One RTSP/1.0 (RFC 2326) control connection. Handles OPTIONS/DESCRIBE/SETUP/PLAY/
 * PAUSE/TEARDOWN/GET_PARAMETER. Once PLAYING, the H.264/RTP payload is carried
 * interleaved on this same TCP socket (RFC 2326 10.12, "$" framed) rather than a
 * separate RTP/UDP transport - this needs no extra ports (friendlier to firewalls/NAT
 * for a single LAN device) and keeps this mirroring WyomingTCPServer/WyomingClientHandler:
 * one TCP connection carries the whole session. UDP transport SETUP requests are
 * rejected (461) - see RtspCameraStreamer's docs for the client-side implication.
 *
 * Written as an abstract class with callback methods to match this codebase's
 * long-lived-per-connection convention (WyomingClientHandler/Satellite) rather than a
 * lambda/interface-parameter style.
 */
abstract class RtspSession(
    private val socket: Socket,
) {
    private val receiveChannel = socket.openReadChannel()
    private val sendChannel = socket.openWriteChannel(autoFlush = false)
    private var runSession = true
    private var sessionId: String = ""
    private var playing = false
    private var interleavedChannel = 0

    val remoteAddress: String
        get() = runCatching { socket.remoteAddress.toString() }.getOrDefault("unknown")

    /** Return the SDP body to answer DESCRIBE with. */
    abstract suspend fun onDescribe(): String

    /**
     * Called when a client requests PLAY. Return true to accept - this session becomes
     * the (sole) active viewer and starts receiving RTP - or false to refuse (e.g.
     * another viewer is already connected, or the camera is busy elsewhere).
     */
    abstract suspend fun onPlay(): Boolean

    /** Called once, when this session stops being (or never became) the active viewer. */
    abstract suspend fun onTeardownOrDisconnect()

    suspend fun start() {
        try {
            while (runSession) {
                val request = readRequest() ?: break
                handleRequest(request)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Timber.d("RtspSession[$remoteAddress]: connection ended: $e")
        } finally {
            withContext(NonCancellable) {
                playing = false
                onTeardownOrDisconnect()
                runCatching { socket.close() }
            }
        }
    }

    fun stop() {
        runSession = false
        runCatching { socket.close() }
    }

    private suspend fun readLine(): String? {
        val byte = receiveChannel.readByte()
        if (byte.toInt() == -1) return null
        if (byte == INTERLEAVED_FRAME_MARKER) {
            // A real RTSP client (VLC, ffplay) sends RTCP receiver reports back to us on
            // the interleaved RTCP channel per RFC 2326 10.12 - this is normal, expected
            // traffic once PLAYing, not a malformed request. We don't consume RTCP
            // feedback in this minimal server, so discard the frame and keep reading for
            // the next actual request line rather than tearing the session down.
            skipInterleavedFrame()
            return readLine()
        }
        val sb = StringBuilder()
        sb.append(byte.toInt().toChar())
        val rest = receiveChannel.readLine()
        if (rest != null) sb.append(rest)
        return sb.toString().trimEnd('\r')
    }

    private suspend fun skipInterleavedFrame() {
        // '$' <channel:1><length:2 big-endian><payload> already had its '$' consumed by
        // the caller; read and discard the rest of the frame.
        receiveChannel.readByte() // channel number, unused
        val lenHigh = receiveChannel.readByte().toInt() and 0xFF
        val lenLow = receiveChannel.readByte().toInt() and 0xFF
        val length = (lenHigh shl 8) or lenLow
        if (length > 0) {
            receiveChannel.readPacket(length)
        }
    }

    private suspend fun readRequest(): RtspRequest? {
        val requestLine = readLine() ?: return null
        if (requestLine.isBlank()) return readRequest() // tolerate stray blank keep-alive lines
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            Timber.w("RtspSession[$remoteAddress]: malformed request line: $requestLine")
            return null
        }
        val method = parts[0]
        val uri = parts[1]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers["CONTENT-LENGTH"]?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            // We do not support any method that sends a body (e.g. RECORD); drain and
            // discard it so the connection stays in sync for the next request.
            receiveChannel.readPacket(contentLength)
        }

        return RtspRequest(method, uri, headers["CSEQ"] ?: "0", headers)
    }

    private suspend fun handleRequest(request: RtspRequest) {
        Timber.d("RtspSession[$remoteAddress]: ${request.method} ${request.uri} (CSeq ${request.cSeq})")
        when (request.method) {
            "OPTIONS" -> respond(
                200, request.cSeq,
                extraHeaders = mapOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER")
            )
            "DESCRIBE" -> {
                val sdp = onDescribe()
                respond(
                    200, request.cSeq,
                    extraHeaders = mapOf("Content-Base" to request.uri, "Content-Type" to "application/sdp"),
                    body = sdp
                )
            }
            "SETUP" -> handleSetup(request)
            "PLAY" -> {
                if (onPlay()) {
                    playing = true
                    respond(200, request.cSeq, extraHeaders = mapOf("Session" to sessionId, "Range" to "npt=0.000-"))
                } else {
                    respond(503, request.cSeq)
                }
            }
            "PAUSE" -> {
                playing = false
                respond(200, request.cSeq, extraHeaders = mapOf("Session" to sessionId))
            }
            "TEARDOWN" -> {
                playing = false
                respond(200, request.cSeq, extraHeaders = mapOf("Session" to sessionId))
                onTeardownOrDisconnect()
                runSession = false
            }
            "GET_PARAMETER" -> respond(200, request.cSeq, extraHeaders = mapOf("Session" to sessionId))
            else -> respond(501, request.cSeq)
        }
    }

    private suspend fun handleSetup(request: RtspRequest) {
        val transport = request.headers["TRANSPORT"] ?: ""
        if (!transport.contains("TCP", ignoreCase = true) || !transport.contains("interleaved", ignoreCase = true)) {
            // Only RTP-over-TCP interleaved is supported (see class doc); a client asking
            // for RTP/AVP UDP transport gets a clear rejection instead of a silent hang.
            // ffplay: add "-rtsp_transport tcp"; VLC and go2rtc already default to TCP.
            respond(461, request.cSeq)
            return
        }
        Regex("interleaved=(\\d+)-(\\d+)").find(transport)?.let {
            interleavedChannel = it.groupValues[1].toIntOrNull() ?: 0
        }
        if (sessionId.isEmpty()) sessionId = Random.nextInt(10_000_000, 99_999_999).toString()
        respond(200, request.cSeq, extraHeaders = mapOf("Transport" to transport, "Session" to sessionId))
    }

    /** Sends one RTP packet interleaved on this session's TCP socket (RFC 2326 10.12). */
    suspend fun sendInterleavedRtp(packet: ByteArray) {
        if (!playing) return
        withContext(Dispatchers.IO) {
            try {
                val frame = ByteArray(4 + packet.size)
                frame[0] = '$'.code.toByte()
                frame[1] = interleavedChannel.toByte()
                frame[2] = ((packet.size shr 8) and 0xFF).toByte()
                frame[3] = (packet.size and 0xFF).toByte()
                System.arraycopy(packet, 0, frame, 4, packet.size)
                sendChannel.writeByteArray(frame)
                sendChannel.flush()
            } catch (e: Exception) {
                Timber.w("RtspSession[$remoteAddress]: error sending RTP, tearing down: $e")
                playing = false
                runSession = false
            }
        }
    }

    private suspend fun respond(code: Int, cSeq: String, extraHeaders: Map<String, String> = emptyMap(), body: String? = null) {
        val reason = REASONS[code] ?: "Unknown"
        val sb = StringBuilder()
        sb.append("RTSP/1.0 $code $reason\r\n")
        sb.append("CSeq: $cSeq\r\n")
        extraHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        if (bodyBytes != null) {
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
        }
        sb.append("\r\n")

        withContext(Dispatchers.IO) {
            try {
                sendChannel.writeByteArray(sb.toString().toByteArray(Charsets.UTF_8))
                if (bodyBytes != null) sendChannel.writeByteArray(bodyBytes)
                sendChannel.flush()
            } catch (e: Exception) {
                Timber.w("RtspSession[$remoteAddress]: error writing response: $e")
                runSession = false
            }
        }
    }

    companion object {
        private val INTERLEAVED_FRAME_MARKER = '$'.code.toByte()
        private val REASONS = mapOf(
            200 to "OK",
            461 to "Unsupported Transport",
            501 to "Not Implemented",
            503 to "Service Unavailable",
        )
    }
}
