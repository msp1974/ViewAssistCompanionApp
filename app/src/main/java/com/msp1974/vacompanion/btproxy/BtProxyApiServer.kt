package com.msp1974.vacompanion.btproxy

import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM server for the ESPHome native API (plaintext framing), exposing only what Home
 * Assistant needs to use this device as a Bluetooth proxy. It owns the sockets and protocol
 * housekeeping (hello, device info, ping, subscriptions); Bluetooth work is delegated to the
 * abstract callbacks, in keeping with the other long-lived components in this app.
 *
 * Each client gets its own bounded write queue and writer thread so one stalled client cannot
 * block the scanner or other clients. Encrypted (Noise) clients are rejected - leave the
 * encryption key empty when adding the device in Home Assistant.
 */
abstract class BtProxyApiServer(private val identity: ProxyIdentity) {

    /** One connected Home Assistant instance. */
    inner class Session internal constructor(internal val socket: Socket, val id: Int) {
        internal val queue = LinkedBlockingQueue<Frame>(MAX_QUEUE)

        @Volatile internal var advertisementsSubscribed = false
        @Volatile internal var connectionsFreeSubscribed = false
        @Volatile internal var lastRx = System.currentTimeMillis()
        @Volatile internal var closed = false

        val remote: String get() = socket.remoteSocketAddress.toString()

        /** Queue a message; returns false if the session is gone or too far behind. */
        fun send(type: Int, payload: ByteArray = ByteArray(0)): Boolean {
            if (closed) return false
            return queue.offer(Frame(type, payload))
        }

        fun close() = closeSession(this)
    }

    internal class Frame(val type: Int, val payload: ByteArray)

    private val sessions = CopyOnWriteArrayList<Session>()
    private val nextId = AtomicInteger(1)
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    val sessionCount: Int get() = sessions.size

    /** Sessions currently wanting advertisements; the scanner should run while this is above 0. */
    val advertisementSubscriberCount: Int get() = sessions.count { it.advertisementsSubscribed }

    // --- Callbacks ---

    /** The number of sessions wanting advertisements changed (0 = scanner can stop). */
    abstract fun onAdvertisementSubscribers(count: Int)

    /** A client asked for the scanner mode to change (true = active). */
    abstract fun onScannerModeRequested(active: Boolean)

    /** A client subscribed to scanner state; send it the current state. */
    abstract fun onScannerStateRequested(session: Session)

    /** A client subscribed to free connection slot updates; send it the current state. */
    abstract fun onConnectionsFreeRequested(session: Session)

    /** A BLE device / GATT request (ids 70-78, 88) from a client. */
    abstract fun onGattMessage(session: Session, type: Int, fields: ProtoFields)

    /** A session ended; release anything it owned (e.g. GATT connections). */
    abstract fun onSessionClosed(session: Session)

    open fun onLog(message: String) {}

    // --- Lifecycle ---

    fun start(port: Int): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            running = true
            Thread({ acceptLoop(socket) }, "btproxy-accept").start()
            Thread({ keepAliveLoop() }, "btproxy-keepalive").apply { isDaemon = true }.start()
            Timber.i("Bluetooth proxy API listening on port $port")
            true
        } catch (e: Exception) {
            Timber.e("Bluetooth proxy: failed to listen on port $port: $e")
            false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        sessions.toList().forEach { closeSession(it) }
    }

    // --- Sending ---

    fun broadcast(type: Int, payload: ByteArray, filter: (Session) -> Boolean = { true }) {
        sessions.forEach { if (filter(it)) it.send(type, payload) }
    }

    fun sendAdvertisements(ads: List<BleAdvertisement>) {
        if (ads.isEmpty()) return
        val payload = ApiMessages.rawAdvertisements(ads)
        sessions.forEach { s ->
            // Advertisements are best-effort: drop rather than let a slow client's queue grow.
            if (s.advertisementsSubscribed && s.queue.remainingCapacity() > MAX_QUEUE / 2) {
                s.send(MsgId.BLE_RAW_ADVERTISEMENTS_RESPONSE, payload)
            }
        }
    }

    fun broadcastScannerState(payload: ByteArray) =
        broadcast(MsgId.BLE_SCANNER_STATE_RESPONSE, payload) { it.advertisementsSubscribed }

    fun broadcastConnectionsFree(payload: ByteArray) =
        broadcast(MsgId.BLE_CONNECTIONS_FREE_RESPONSE, payload) { it.connectionsFreeSubscribed }

    // --- Internals ---

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (running) Timber.w("Bluetooth proxy accept failed: $e")
                return
            }
            if (sessions.size >= MAX_SESSIONS) {
                Timber.w("Bluetooth proxy: too many clients, rejecting ${client.remoteSocketAddress}")
                try { client.close() } catch (_: IOException) {}
                continue
            }
            client.tcpNoDelay = true
            client.keepAlive = true
            val session = Session(client, nextId.getAndIncrement())
            sessions.add(session)
            onLog("Client connected: ${session.remote}")
            Thread({ writerLoop(session) }, "btproxy-w-${session.id}").start()
            Thread({ readerLoop(session) }, "btproxy-r-${session.id}").start()
        }
    }

    private fun writerLoop(session: Session) {
        try {
            val out = BufferedOutputStream(session.socket.getOutputStream(), 8192)
            while (!session.closed) {
                val frame = session.queue.poll(1, TimeUnit.SECONDS) ?: continue
                writeFrame(out, frame)
                // Coalesce whatever else is already waiting into the same flush.
                while (true) {
                    writeFrame(out, session.queue.poll() ?: break)
                }
                out.flush()
            }
        } catch (e: Exception) {
            if (!session.closed) Timber.d("Bluetooth proxy write error (${session.remote}): $e")
        } finally {
            closeSession(session)
        }
    }

    private fun writeFrame(out: BufferedOutputStream, frame: Frame) {
        val header = ByteArrayOutputStream(8)
        header.write(0)
        header.writeFrameVarint(frame.payload.size)
        header.writeFrameVarint(frame.type)
        out.write(header.toByteArray())
        out.write(frame.payload)
    }

    private fun readerLoop(session: Session) {
        try {
            val input = BufferedInputStream(session.socket.getInputStream(), 8192)
            while (!session.closed) {
                val preamble = input.read()
                if (preamble < 0) break
                if (preamble != 0) {
                    // 0x01 = Noise encrypted. Not supported - see class docs.
                    onLog("Rejected client ${session.remote}: encryption is not supported")
                    break
                }
                val length = input.readFrameVarint()
                val type = input.readFrameVarint()
                if (length < 0 || length > MAX_FRAME) throw IOException("Frame too large: $length")
                val payload = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(payload, read, length - read)
                    if (n < 0) throw IOException("Connection closed")
                    read += n
                }
                session.lastRx = System.currentTimeMillis()
                handleMessage(session, type, payload)
            }
        } catch (e: Exception) {
            if (!session.closed) Timber.d("Bluetooth proxy read error (${session.remote}): $e")
        } finally {
            closeSession(session)
        }
    }

    private fun handleMessage(session: Session, type: Int, payload: ByteArray) {
        // A malformed known message is logged and ignored; it must not end the session.
        val fields = try {
            ProtoFields.parse(payload)
        } catch (e: IOException) {
            Timber.w("Bluetooth proxy: malformed message type $type: $e")
            return
        }

        when (type) {
            MsgId.HELLO_REQUEST -> {
                onLog("Hello from '${fields.string(1)}'")
                session.send(MsgId.HELLO_RESPONSE, ApiMessages.helloResponse(identity))
            }
            MsgId.CONNECT_REQUEST -> session.send(MsgId.CONNECT_RESPONSE)
            MsgId.DISCONNECT_REQUEST -> {
                session.send(MsgId.DISCONNECT_RESPONSE)
                // Let the writer flush the response before the socket goes away.
                Thread {
                    try { Thread.sleep(200) } catch (_: InterruptedException) {}
                    closeSession(session)
                }.start()
            }
            MsgId.DISCONNECT_RESPONSE -> closeSession(session)
            MsgId.PING_REQUEST -> session.send(MsgId.PING_RESPONSE)
            MsgId.PING_RESPONSE -> {}
            MsgId.DEVICE_INFO_REQUEST ->
                session.send(MsgId.DEVICE_INFO_RESPONSE, ApiMessages.deviceInfoResponse(identity))
            // The proxy exposes no entities.
            MsgId.LIST_ENTITIES_REQUEST -> session.send(MsgId.LIST_ENTITIES_DONE)

            MsgId.SUBSCRIBE_BLE_ADVERTISEMENTS_REQUEST -> {
                // Only raw advertisements (flag bit 0) are produced; that is what current Home
                // Assistant requests from proxies advertising RAW_ADVERTISEMENTS.
                session.advertisementsSubscribed = true
                onAdvertisementSubscribers(sessions.count { it.advertisementsSubscribed })
                onScannerStateRequested(session)
            }
            MsgId.UNSUBSCRIBE_BLE_ADVERTISEMENTS_REQUEST -> {
                session.advertisementsSubscribed = false
                onAdvertisementSubscribers(sessions.count { it.advertisementsSubscribed })
            }
            MsgId.BLE_SCANNER_SET_MODE_REQUEST -> onScannerModeRequested(fields.int(1) == 1)
            MsgId.SUBSCRIBE_BLE_CONNECTIONS_FREE_REQUEST -> {
                session.connectionsFreeSubscribed = true
                onConnectionsFreeRequested(session)
            }

            MsgId.BLE_DEVICE_REQUEST,
            MsgId.BLE_GATT_GET_SERVICES_REQUEST,
            MsgId.BLE_GATT_READ_REQUEST,
            MsgId.BLE_GATT_WRITE_REQUEST,
            MsgId.BLE_GATT_READ_DESCRIPTOR_REQUEST,
            MsgId.BLE_GATT_WRITE_DESCRIPTOR_REQUEST,
            MsgId.BLE_GATT_NOTIFY_REQUEST -> onGattMessage(session, type, fields)

            // Everything else (log/state/service subscriptions, entity commands, ...) is not
            // applicable to a proxy-only node; unknown messages are ignored by design.
            else -> {}
        }
    }

    private fun keepAliveLoop() {
        while (running) {
            try {
                Thread.sleep(KEEPALIVE_CHECK_MS)
            } catch (_: InterruptedException) {
                return
            }
            val now = System.currentTimeMillis()
            sessions.forEach { s ->
                val idle = now - s.lastRx
                // Home Assistant pings every ~20s; a client silent for this long is gone.
                if (idle > SESSION_TIMEOUT_MS) {
                    onLog("Client ${s.remote} timed out")
                    closeSession(s)
                } else if (idle > PING_AFTER_MS) {
                    s.send(MsgId.PING_REQUEST)
                }
            }
        }
    }

    private fun closeSession(session: Session) {
        if (session.closed) return
        session.closed = true
        try { session.socket.close() } catch (_: IOException) {}
        if (sessions.remove(session)) {
            onLog("Client disconnected: ${session.remote}")
            val wasSubscribed = session.advertisementsSubscribed
            session.advertisementsSubscribed = false
            try {
                onSessionClosed(session)
                if (wasSubscribed) onAdvertisementSubscribers(sessions.count { it.advertisementsSubscribed })
            } catch (e: Exception) {
                Timber.w("Bluetooth proxy: error closing session: $e")
            }
        }
    }

    companion object {
        private const val MAX_SESSIONS = 8
        private const val MAX_QUEUE = 512
        private const val MAX_FRAME = 1 shl 20
        private const val KEEPALIVE_CHECK_MS = 10_000L
        private const val PING_AFTER_MS = 45_000L
        private const val SESSION_TIMEOUT_MS = 120_000L
    }
}
