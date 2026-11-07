package com.msp1974.vacompanion.btproxy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.util.IdentityHashMap

/**
 * Executes GATT requests from Home Assistant against local Bluetooth devices.
 *
 * A connection belongs to the [BtProxyApiServer.Session] that opened it and all of its responses
 * go only to that session. Android allows one outstanding GATT operation per connection, so each
 * connection serialises its requests through a small queue. Android identifies attributes by
 * instance id rather than the ATT handles Home Assistant expects, so unique handles are assigned
 * on service discovery and mapped back to the Android objects.
 */
@SuppressLint("MissingPermission")
class GattManager(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val server: BtProxyApiServer,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val connections = HashMap<Long, Connection>()

    val limit = MAX_CONNECTIONS

    /** Number of slots in use, plus the addresses holding them. Thread-safe snapshot. */
    fun allocated(): List<Long> = synchronized(connections) { connections.keys.toList() }

    fun free(): Int = (limit - allocated().size).coerceAtLeast(0)

    fun announceFree() {
        server.broadcastConnectionsFree(ApiMessages.connectionsFree(free(), limit, allocated()))
    }

    fun connectionsFreePayload(): ByteArray = ApiMessages.connectionsFree(free(), limit, allocated())

    // --- Requests from sessions ---

    fun handle(session: BtProxyApiServer.Session, type: Int, fields: ProtoFields) {
        val address = fields.long(1)
        when (type) {
            MsgId.BLE_DEVICE_REQUEST -> handleDeviceRequest(session, address, fields.int(2), fields.int(4))
            else -> {
                val conn = synchronized(connections) { connections[address] }
                if (conn == null || conn.session !== session) {
                    session.send(MsgId.BLE_GATT_ERROR_RESPONSE, ApiMessages.gattError(address, fields.int(2), ERR_NOT_CONNECTED))
                    return
                }
                conn.handleRequest(type, fields)
            }
        }
    }

    private fun handleDeviceRequest(session: BtProxyApiServer.Session, address: Long, requestType: Int, addressType: Int) {
        when (requestType) {
            BleDeviceRequest.CONNECT,
            BleDeviceRequest.CONNECT_V3_WITH_CACHE,
            BleDeviceRequest.CONNECT_V3_WITHOUT_CACHE ->
                connect(session, address, addressType, refreshCache = requestType == BleDeviceRequest.CONNECT_V3_WITHOUT_CACHE)

            BleDeviceRequest.DISCONNECT -> {
                val conn = synchronized(connections) { connections[address] }
                if (conn != null && conn.session === session) {
                    conn.disconnect()
                } else {
                    session.send(MsgId.BLE_DEVICE_CONNECTION_RESPONSE, ApiMessages.deviceConnection(address, false, 0, 0))
                }
            }

            BleDeviceRequest.CLEAR_CACHE -> {
                val conn = synchronized(connections) { connections[address] }
                val ok = conn?.refreshCache() ?: false
                session.send(MsgId.BLE_DEVICE_CLEAR_CACHE_RESPONSE, ApiMessages.clearCacheResponse(address, ok))
            }

            // Pairing isn't supported; the feature flag is not advertised so this is unexpected.
            else -> Timber.w("Bluetooth proxy: unsupported device request $requestType")
        }
    }

    private fun connect(session: BtProxyApiServer.Session, address: Long, addressType: Int, refreshCache: Boolean) {
        val device = try {
            adapter?.takeIf { it.isEnabled }?.let {
                val addr = BleAddress.toString(address)
                // getRemoteDevice(String) assumes a public address; random-address devices need the type.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    (addressType == BluetoothDevice.ADDRESS_TYPE_PUBLIC || addressType == BluetoothDevice.ADDRESS_TYPE_RANDOM)
                ) {
                    it.getRemoteLeDevice(addr, addressType)
                } else {
                    it.getRemoteDevice(addr)
                }
            }
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            session.send(MsgId.BLE_DEVICE_CONNECTION_RESPONSE, ApiMessages.deviceConnection(address, false, 0, ERR_GENERIC))
            return
        }
        val conn = synchronized(connections) {
            val existing = connections[address]
            if (existing != null) {
                // Already connected through this proxy: report it rather than opening twice.
                if (existing.session === session && existing.connected) {
                    session.send(MsgId.BLE_DEVICE_CONNECTION_RESPONSE, ApiMessages.deviceConnection(address, true, existing.mtu, 0))
                } else {
                    session.send(MsgId.BLE_DEVICE_CONNECTION_RESPONSE, ApiMessages.deviceConnection(address, false, 0, ERR_BUSY))
                }
                return
            }
            if (connections.size >= limit) {
                session.send(MsgId.BLE_DEVICE_CONNECTION_RESPONSE, ApiMessages.deviceConnection(address, false, 0, ERR_BUSY))
                return
            }
            Connection(session, address, device, refreshCache).also { connections[address] = it }
        }
        announceFree()
        handler.post { conn.open() }
    }

    /** Drop every connection owned by a session that went away. */
    fun releaseSession(session: BtProxyApiServer.Session) {
        val owned = synchronized(connections) { connections.values.filter { it.session === session } }
        owned.forEach { handler.post { it.close(notify = false) } }
    }

    /** The adapter went away: tell each owning client its connection dropped. */
    fun adapterLost() {
        val all = synchronized(connections) { connections.values.toList() }
        all.forEach { handler.post { it.close(notify = true, error = ERR_GENERIC) } }
    }

    fun shutdown() {
        val all = synchronized(connections) { connections.values.toList() }
        all.forEach { handler.post { it.close(notify = false) } }
    }

    private fun remove(conn: Connection) {
        synchronized(connections) {
            if (connections[conn.address] === conn) connections.remove(conn.address)
        }
        announceFree()
    }

    // --- One GATT connection ---

    private inner class Op(val handle: Int, val kind: Int, val start: () -> Boolean)

    private inner class Connection(
        val session: BtProxyApiServer.Session,
        val address: Long,
        private val device: BluetoothDevice,
        private val refreshOnConnect: Boolean,
    ) {
        var gatt: BluetoothGatt? = null
        @Volatile var connected = false
        @Volatile var mtu = DEFAULT_MTU

        private var closed = false
        private var servicesBuilt = false
        private val characteristics = HashMap<Int, BluetoothGattCharacteristic>()
        private val descriptors = HashMap<Int, BluetoothGattDescriptor>()
        private val handles = IdentityHashMap<Any, Int>()
        private val ops = ArrayDeque<Op>()
        private var current: Op? = null

        private val connectTimeout = Runnable {
            Timber.w("Bluetooth proxy: connect timeout for ${BleAddress.toString(address)}")
            close(notify = true, error = ERR_TIMEOUT)
        }
        private val opTimeout = Runnable {
            val op = current ?: return@Runnable
            Timber.w("Bluetooth proxy: GATT operation timed out (kind ${op.kind})")
            session.send(MsgId.BLE_GATT_ERROR_RESPONSE, ApiMessages.gattError(address, op.handle, ERR_TIMEOUT))
            current = null
            runNext()
        }

        fun open() {
            if (closed) return
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            try {
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) close(notify = true, error = ERR_GENERIC)
            } catch (e: Exception) {
                Timber.w("Bluetooth proxy: connectGatt failed: $e")
                close(notify = true, error = ERR_GENERIC)
            }
        }

        fun disconnect() {
            // The disconnect callback reports the result to Home Assistant.
            val g = gatt
            if (g == null || !connected) close(notify = true) else g.disconnect()
        }

        /** Android hides refresh(); reflection is the only way to drop its service cache. */
        fun refreshCache(): Boolean = try {
            gatt?.let { it.javaClass.getMethod("refresh").invoke(it) as? Boolean } ?: false
        } catch (e: Exception) {
            false
        }

        fun close(notify: Boolean, error: Int = 0) {
            if (closed) return
            closed = true
            connected = false
            handler.removeCallbacks(connectTimeout)
            handler.removeCallbacks(opTimeout)
            ops.clear()
            current = null
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (e: Exception) {
                Timber.d("Bluetooth proxy: error closing gatt: $e")
            }
            gatt = null
            remove(this)
            if (notify) {
                session.send(MsgId.BLE_DEVICE_CONNECTION_RESPONSE, ApiMessages.deviceConnection(address, false, 0, error))
            }
        }

        // --- Requests ---

        fun handleRequest(type: Int, f: ProtoFields) {
            handler.post {
                if (closed || !connected) {
                    session.send(MsgId.BLE_GATT_ERROR_RESPONSE, ApiMessages.gattError(address, f.int(2), ERR_NOT_CONNECTED))
                    return@post
                }
                when (type) {
                    MsgId.BLE_GATT_GET_SERVICES_REQUEST -> getServices()
                    MsgId.BLE_GATT_READ_REQUEST -> readCharacteristic(f.int(2))
                    MsgId.BLE_GATT_WRITE_REQUEST -> writeCharacteristic(f.int(2), f.bytes(4), f.bool(3))
                    MsgId.BLE_GATT_READ_DESCRIPTOR_REQUEST -> readDescriptor(f.int(2))
                    MsgId.BLE_GATT_WRITE_DESCRIPTOR_REQUEST -> writeDescriptor(f.int(2), f.bytes(3))
                    MsgId.BLE_GATT_NOTIFY_REQUEST -> setNotify(f.int(2), f.bool(3))
                }
            }
        }

        private fun getServices() {
            // Services are discovered and numbered before the connection is reported, so this
            // only ever replays them.
            if (servicesBuilt) sendServices()
            else session.send(MsgId.BLE_GATT_ERROR_RESPONSE, ApiMessages.gattError(address, 0, ERR_GENERIC))
        }

        /**
         * Number every attribute. Home Assistant may reuse handles from its services cache without
         * asking for them again, so they must be the same on every connection: Android's instance
         * id is the real ATT handle on the AOSP stack. Anything without a usable id (0, or a
         * duplicate) gets a number above the highest id so it can't collide.
         */
        private fun buildHandles() {
            characteristics.clear()
            descriptors.clear()
            handles.clear()
            val services = gatt?.services.orEmpty()
            var highest = 0
            services.forEach { s ->
                highest = maxOf(highest, s.instanceId)
                s.characteristics.forEach { c ->
                    highest = maxOf(highest, c.instanceId)
                    c.descriptors.forEach { d -> highest = maxOf(highest, instanceIdOf(d)) }
                }
            }
            var next = highest + 1
            val used = HashSet<Int>()
            fun assign(attr: Any, instanceId: Int): Int {
                val handle = if (instanceId > 0 && used.add(instanceId)) instanceId else next++
                handles[attr] = handle
                return handle
            }
            services.forEach { service ->
                assign(service, service.instanceId)
                service.characteristics.forEach { c ->
                    characteristics[assign(c, c.instanceId)] = c
                    c.descriptors.forEach { d -> descriptors[assign(d, instanceIdOf(d))] = d }
                }
            }
            servicesBuilt = true
        }

        /** Descriptors have no public instance id; read the hidden one, or 0 to fall back to numbering. */
        private fun instanceIdOf(d: BluetoothGattDescriptor): Int = try {
            d.javaClass.getMethod("getInstanceId").invoke(d) as? Int ?: 0
        } catch (e: Throwable) {
            0
        }

        private fun handleOf(c: BluetoothGattCharacteristic): Int = handles[c] ?: 0

        private fun sendServices() {
            gatt?.services?.forEach { service ->
                val chars = service.characteristics.map { c ->
                    val descs = c.descriptors.map { d -> GattDescriptorInfo(d.uuid, handles[d] ?: 0) }
                    GattCharacteristicInfo(c.uuid, handles[c] ?: 0, c.properties, descs)
                }
                session.send(
                    MsgId.BLE_GATT_GET_SERVICES_RESPONSE,
                    ApiMessages.gattService(address, GattServiceInfo(service.uuid, handles[service] ?: 0, chars))
                )
            }
            session.send(MsgId.BLE_GATT_GET_SERVICES_DONE, ApiMessages.addressOnly(address))
        }

        private fun readCharacteristic(handle: Int) {
            val c = characteristics[handle] ?: return unknownHandle(handle)
            enqueue(Op(handle, OP_READ) { gatt?.readCharacteristic(c) == true })
        }

        private fun writeCharacteristic(handle: Int, data: ByteArray, withResponse: Boolean) {
            val c = characteristics[handle] ?: return unknownHandle(handle)
            val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            enqueue(Op(handle, if (withResponse) OP_WRITE else OP_WRITE_NO_RESPONSE) {
                val g = gatt
                if (g == null) false
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(c, data, writeType) == 0 // BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = writeType
                    @Suppress("DEPRECATION")
                    c.value = data
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
            })
        }

        private fun readDescriptor(handle: Int) {
            val d = descriptors[handle] ?: return unknownHandle(handle)
            enqueue(Op(handle, OP_READ_DESCRIPTOR) { gatt?.readDescriptor(d) == true })
        }

        private fun writeDescriptor(handle: Int, data: ByteArray) {
            val d = descriptors[handle] ?: return unknownHandle(handle)
            enqueue(Op(handle, OP_WRITE_DESCRIPTOR) { writeDescriptorNow(d, data) })
        }

        private fun writeDescriptorNow(d: BluetoothGattDescriptor, data: ByteArray): Boolean {
            val g = gatt ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, data) == 0
            } else {
                @Suppress("DEPRECATION")
                d.value = data
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }

        private fun setNotify(handle: Int, enable: Boolean) {
            val c = characteristics[handle] ?: return unknownHandle(handle)
            val g = gatt ?: return
            if (!g.setCharacteristicNotification(c, enable)) {
                session.send(MsgId.BLE_GATT_ERROR_RESPONSE, ApiMessages.gattError(address, handle, ERR_GENERIC))
                return
            }
            // Home Assistant writes the CCCD itself (as ESPHome expects), so only the local
            // delivery of notifications is switched on here.
            session.send(MsgId.BLE_GATT_NOTIFY_RESPONSE, ApiMessages.addressHandle(address, handle))
        }

        private fun unknownHandle(handle: Int) {
            session.send(MsgId.BLE_GATT_ERROR_RESPONSE, ApiMessages.gattError(address, handle, ERR_INVALID_HANDLE))
        }

        // --- Operation queue ---

        private fun enqueue(op: Op) {
            ops.addLast(op)
            if (current == null) runNext()
        }

        private fun runNext() {
            handler.removeCallbacks(opTimeout)
            val op = ops.removeFirstOrNull() ?: return
            current = op
            val started = try {
                op.start()
            } catch (e: Exception) {
                Timber.w("Bluetooth proxy: GATT op failed to start: $e")
                false
            }
            if (!started) {
                session.send(MsgId.BLE_GATT_ERROR_RESPONSE, ApiMessages.gattError(address, op.handle, ERR_GENERIC))
                current = null
                runNext()
            } else {
                handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
            }
        }

        /** An operation finished (successfully or not); respond and start the next one. */
        private fun complete(kind: Int, status: Int, data: ByteArray? = null) {
            val op = current
            if (op == null || op.kind != kind) return
            current = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                session.send(MsgId.BLE_GATT_ERROR_RESPONSE, ApiMessages.gattError(address, op.handle, status))
            } else when (kind) {
                OP_READ -> session.send(MsgId.BLE_GATT_READ_RESPONSE, ApiMessages.gattData(address, op.handle, data ?: ByteArray(0)))
                OP_READ_DESCRIPTOR -> session.send(MsgId.BLE_GATT_READ_RESPONSE, ApiMessages.gattData(address, op.handle, data ?: ByteArray(0)))
                OP_WRITE, OP_WRITE_DESCRIPTOR -> session.send(MsgId.BLE_GATT_WRITE_RESPONSE, ApiMessages.addressHandle(address, op.handle))
            }
            runNext()
        }

        // --- Android callbacks (delivered on binder threads; hop to the main handler) ---

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                handler.post {
                    if (closed) return@post
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        handler.removeCallbacks(connectTimeout)
                        connected = true
                        if (refreshOnConnect) refreshCache()
                        // Ask for a larger MTU; the connection is reported once that settles.
                        if (!g.requestMtu(PREFERRED_MTU)) discoverServices(g)
                    } else {
                        Timber.d("Bluetooth proxy: ${BleAddress.toString(address)} disconnected (status $status)")
                        close(notify = true, error = if (status == BluetoothGatt.GATT_SUCCESS) 0 else status)
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                handler.post {
                    if (closed) return@post
                    if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                    discoverServices(g)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                handler.post {
                    if (closed) return@post
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Handles must exist before Home Assistant (which may skip GET_SERVICES
                        // using its cache) sends its first read, write or notify.
                        buildHandles()
                        reportConnected()
                    } else {
                        close(notify = true, error = status)
                    }
                }
            }

            // API 33+ delivers the value as an argument; older versions read it off the object.
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                handler.post { complete(OP_READ, status, value) }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                @Suppress("DEPRECATION")
                val value = c.value ?: ByteArray(0)
                handler.post { complete(OP_READ, status, value) }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                handler.post { current?.let { complete(it.kind, status) } }
            }

            override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int, value: ByteArray) {
                handler.post { complete(OP_READ_DESCRIPTOR, status, value) }
            }

            @Deprecated("Deprecated in API 33")
            override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                @Suppress("DEPRECATION")
                val value = d.value ?: ByteArray(0)
                handler.post { complete(OP_READ_DESCRIPTOR, status, value) }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                // A CCCD write is the tail of a notify request; anything else is a plain write.
                handler.post { current?.let { complete(it.kind, status) } }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                notifyData(c, value)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                @Suppress("DEPRECATION")
                notifyData(c, c.value ?: ByteArray(0))
            }
        }

        private fun notifyData(c: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post {
                val handle = handleOf(c)
                if (!closed && handle != 0) {
                    session.send(MsgId.BLE_GATT_NOTIFY_DATA_RESPONSE, ApiMessages.gattData(address, handle, value))
                }
            }
        }

        private fun discoverServices(g: BluetoothGatt) {
            if (!g.discoverServices()) close(notify = true, error = ERR_GENERIC)
        }

        private fun reportConnected() {
            session.send(MsgId.BLE_DEVICE_CONNECTION_RESPONSE, ApiMessages.deviceConnection(address, true, mtu, 0))
        }
    }

    companion object {
        // Android supports roughly seven concurrent LE connections; stay below that so scanning
        // keeps working while connected, matching ESPHome's default of three.
        const val MAX_CONNECTIONS = 3
        private const val PREFERRED_MTU = 247
        private const val DEFAULT_MTU = 23
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val OP_TIMEOUT_MS = 15_000L

        private const val OP_READ = 1
        private const val OP_WRITE = 2
        private const val OP_WRITE_NO_RESPONSE = 3
        private const val OP_READ_DESCRIPTOR = 4
        private const val OP_WRITE_DESCRIPTOR = 5

        // ESP-IDF style codes Home Assistant understands.
        private const val ERR_GENERIC = 0x85
        private const val ERR_BUSY = 0x81
        private const val ERR_TIMEOUT = 0x93
        private const val ERR_INVALID_HANDLE = 0x01
        private const val ERR_NOT_CONNECTED = 0x85
    }
}
