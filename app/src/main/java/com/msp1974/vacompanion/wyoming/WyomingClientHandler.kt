package com.msp1974.vacompanion.wyoming

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readLine
import io.ktor.utils.io.readPacket
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber
import java.net.SocketException
import kotlin.time.Duration.Companion.seconds

const val WATCHDOG_SOCKET_TIMEOUT = 5000L
interface IClientHandler {
    suspend fun onWyomingMessage(clientId: String, message: WyomingPacket) {}
    suspend fun onClientDisconnected(clientId: String) {}
}

abstract class WyomingClientHandler (
    val scope: CoroutineScope,
    val socket: Socket
): IClientHandler {
    var runClient: Boolean = true
    val socketId = runCatching { socket.remoteAddress.port().toString() }
        .getOrElse { "unknown-${System.nanoTime()}" }
    val clientId = socketId
    var lastMessage: Long = System.currentTimeMillis()
    private lateinit var watchDogJob: Job
    private lateinit var pingJob: Job
    private lateinit var writeHandlerJob: Job

    val receiveChannel = socket.openReadChannel()
    val sendChannel = socket.openWriteChannel(autoFlush = false)

    private var writeBuffer = Channel<WyomingPacket>(capacity = 100)

    private val json = Json { ignoreUnknownKeys = true }

    init {
        scope.launch {
            start()
        }
    }

    val clientIP: String
        get() {
            try {
                return socket.remoteAddress.toString().split(":")[0].replace("/", "")
            } catch (_: Exception) {
                return ""
            }
        }

    suspend fun start() {
        try {
            writeHandlerJob = scope.launch {
                while(runClient) {
                    select {
                        writeBuffer.onReceive { packet ->
                            writeMessageInternal(packet)
                        }
                    }
                }
            }

            watchDogJob = scope.launch {
                watchDogProcess(WATCHDOG_SOCKET_TIMEOUT)
            }

            pingJob = scope.launch {
                pinger()
            }

            while (runClient) {
                val header = StringBuilder()
                val byte = receiveChannel.readByte()
                if (byte.toInt() == -1) throw java.io.EOFException("Connection closed by peer")

                header.append(byte.toInt().toChar())

                val remaining = receiveChannel.readLine()
                if (remaining != null) header.append(remaining)


                if (!header.isEmpty()) {
                    lastMessage = System.currentTimeMillis()
                    val message = readMessage(header.toString())
                    if (message != null) {
                        onWyomingMessage(socketId, message)
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        } finally {
            withContext(NonCancellable) {
                watchDogJob.cancel()
                pingJob.cancel()
                socket.close()
                onClientDisconnected(socketId)
            }
        }
    }

    fun stop() {
        runClient = false
    }

    private suspend fun pinger() {
        while(runClient) {
            delay(3.seconds)
            val packet = WyomingPacket( WyomingEvent.PING, buildJsonObject { put("text","") })
            if (runClient)  writeMessage(packet)
        }
    }

    private suspend fun watchDogProcess(timeout: Long) {
        try {
            while (runClient) {
                delay(1.seconds)
                if (System.currentTimeMillis() - lastMessage > timeout) {
                    Timber.w("Watchdog timeout for client: $clientId. Disconnecting")
                    runClient = false
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        }
    }

    private suspend fun readMessage(message: String): WyomingPacket? {
        try {
            val header = json.parseToJsonElement(message).jsonObject
            val type = header["type"]?.jsonPrimitive?.content ?: return null

            val dataLength = header["data_length"]?.jsonPrimitive?.intOrNull ?: 0
            val data = if (dataLength > 0) {
                val dataBytes = receiveChannel.readPacket(dataLength)
                json.parseToJsonElement(String(dataBytes.readByteArray())).jsonObject
            } else {
                header["data"]?.jsonObject ?: buildJsonObject {}
            }

            val packet = WyomingPacket(type, data)

            // Read payload
            val payloadLength = header["payload_length"]?.jsonPrimitive?.intOrNull ?: 0
            if (payloadLength != 0) {
                val payloadBytes = receiveChannel.readPacket(payloadLength)
                packet.payload = payloadBytes.readByteArray()
            }
            return packet

        } catch (ex: Exception) {
            Timber.e("Message read exception ${ex.toString().substring(0, ex.toString().length.coerceAtMost(50))}")
        }
        return null
    }

    fun writeMessage(packet: WyomingPacket) {
        if (!runClient) {
            Timber.w("Dropping packet while client is stopping: type=${packet.type}, client=$clientId")
            return
        }

        val result = writeBuffer.trySend(packet)
        if (!result.isSuccess) {
            // Audio chunks can be dropped under pressure, but control messages must be delivered
            // or the HA side can miss state transitions (e.g. wake detected/run pipeline).
            if (packet.type == "audio-chunk") {
                Timber.w("Dropping audio-chunk due to full output buffer: client=$clientId")
                return
            }

            Timber.w("Output buffer full for control packet type=${packet.type}, retrying with timeout")
            scope.launch {
                val sent = withTimeoutOrNull(500L) {
                    writeBuffer.send(packet)
                    true
                } ?: false
                if (!sent) {
                    Timber.e("Failed to enqueue control packet type=${packet.type}; forcing reconnect for client=$clientId")
                    runClient = false
                }
            }
        }

    }

    private suspend fun writeMessageInternal(packet: WyomingPacket) {

        val version = "1.0.0"
        if (sendChannel.isClosedForWrite) {
            Timber.e("Socket closed for write [$socketId]")
            runClient = false
            return
        }

        withContext(Dispatchers.IO) {
            val dataBytes = packet.data.toString().toByteArray(Charsets.UTF_8)

            val header = buildJsonObject {
                put("type", packet.type)
                put("version", version)
                if (dataBytes.isNotEmpty()) {
                    put("data_length", dataBytes.size)
                }
                if (packet.payload.isNotEmpty()) {
                    put("payload_length", packet.payload.size)
                }
            }

            val jsonLine = header.toString() + "\n"

            try {
                sendChannel.writeByteArray(jsonLine.toByteArray(Charsets.UTF_8))
                if (dataBytes.isNotEmpty()) sendChannel.writeByteArray(dataBytes)
                if (packet.payload.isNotEmpty()) sendChannel.writeByteArray(packet.payload)
                sendChannel.flush()
            } catch (ex: SocketException) {
                Timber.e("Socket error sending message: $ex")
                runClient = false
            } catch (ex: Exception) {
                Timber.e("Unknown error sending message: $jsonLine - $ex")
                runClient = false
            }
        }
    }
}
