package com.msp1974.vacompanion.btproxy

/** ESPHome native API message ids (api.proto) used by the Bluetooth proxy. */
object MsgId {
    const val HELLO_REQUEST = 1
    const val HELLO_RESPONSE = 2
    const val CONNECT_REQUEST = 3
    const val CONNECT_RESPONSE = 4
    const val DISCONNECT_REQUEST = 5
    const val DISCONNECT_RESPONSE = 6
    const val PING_REQUEST = 7
    const val PING_RESPONSE = 8
    const val DEVICE_INFO_REQUEST = 9
    const val DEVICE_INFO_RESPONSE = 10
    const val LIST_ENTITIES_REQUEST = 11
    const val LIST_ENTITIES_DONE = 19

    const val BLE_DEVICE_CONNECTION_RESPONSE = 69
    const val BLE_GATT_GET_SERVICES_REQUEST = 70
    const val BLE_GATT_GET_SERVICES_RESPONSE = 71
    const val BLE_GATT_GET_SERVICES_DONE = 72
    const val BLE_GATT_READ_REQUEST = 73
    const val BLE_GATT_READ_RESPONSE = 74
    const val BLE_GATT_WRITE_REQUEST = 75
    const val BLE_GATT_READ_DESCRIPTOR_REQUEST = 76
    const val BLE_GATT_WRITE_DESCRIPTOR_REQUEST = 77
    const val BLE_GATT_NOTIFY_REQUEST = 78
    const val BLE_GATT_NOTIFY_DATA_RESPONSE = 79
    const val SUBSCRIBE_BLE_CONNECTIONS_FREE_REQUEST = 80
    const val BLE_CONNECTIONS_FREE_RESPONSE = 81
    const val BLE_GATT_ERROR_RESPONSE = 82
    const val BLE_GATT_WRITE_RESPONSE = 83
    const val BLE_GATT_NOTIFY_RESPONSE = 84
    const val BLE_DEVICE_PAIRING_RESPONSE = 85
    const val BLE_DEVICE_UNPAIRING_RESPONSE = 86
    const val SUBSCRIBE_BLE_ADVERTISEMENTS_REQUEST = 66
    const val BLE_DEVICE_REQUEST = 68
    const val UNSUBSCRIBE_BLE_ADVERTISEMENTS_REQUEST = 87
    const val BLE_DEVICE_CLEAR_CACHE_RESPONSE = 88
    const val BLE_RAW_ADVERTISEMENTS_RESPONSE = 93
    const val BLE_SCANNER_STATE_RESPONSE = 126
    const val BLE_SCANNER_SET_MODE_REQUEST = 127
}

/** `BluetoothDeviceRequestType`. */
object BleDeviceRequest {
    const val CONNECT = 0
    const val DISCONNECT = 1
    const val PAIR = 2
    const val UNPAIR = 3
    const val CONNECT_V3_WITH_CACHE = 4
    const val CONNECT_V3_WITHOUT_CACHE = 5
    const val CLEAR_CACHE = 6
}

/** `BluetoothScannerState` values. */
object ScannerState {
    const val IDLE = 0
    const val STARTING = 1
    const val RUNNING = 2
    const val FAILED = 3
    const val STOPPING = 4
    const val STOPPED = 5
}

/** `BluetoothProxyFeature` flags advertised in DeviceInfoResponse. */
object ProxyFeature {
    const val PASSIVE_SCAN = 1
    const val ACTIVE_CONNECTIONS = 2
    const val REMOTE_CACHING = 4
    const val PAIRING = 8
    const val CACHE_CLEARING = 16
    const val RAW_ADVERTISEMENTS = 32
    const val STATE_AND_MODE = 64
}

/** One advertisement as forwarded to Home Assistant. */
class BleAdvertisement(val address: Long, val rssi: Int, val addressType: Int, val data: ByteArray)

/** Static identity reported to Home Assistant. */
class ProxyIdentity(
    val nodeName: String,
    val friendlyName: String,
    val mac: String,
    val bluetoothMac: String,
    val model: String,
    val manufacturer: String,
    val appVersion: String,
)

/** Encoders for the messages the server sends. Decoding is done with [ProtoFields] at the call site. */
object ApiMessages {
    const val API_VERSION_MAJOR = 1
    const val API_VERSION_MINOR = 10

    // Reported as the "ESPHome version" of the device. Home Assistant only uses it for display and
    // to flag outdated proxies, so keep it at a release that supports every feature advertised below.
    const val ESPHOME_VERSION = "2025.10.0"

    const val FEATURE_FLAGS = ProxyFeature.PASSIVE_SCAN or
        ProxyFeature.ACTIVE_CONNECTIONS or
        ProxyFeature.RAW_ADVERTISEMENTS or
        ProxyFeature.STATE_AND_MODE or
        ProxyFeature.CACHE_CLEARING

    fun helloResponse(identity: ProxyIdentity): ByteArray = ProtoWriter()
        .uint(1, API_VERSION_MAJOR)
        .uint(2, API_VERSION_MINOR)
        .string(3, "VACA ${identity.appVersion}")
        .string(4, identity.nodeName)
        .toByteArray()

    fun deviceInfoResponse(identity: ProxyIdentity): ByteArray = ProtoWriter()
        .string(2, identity.nodeName)
        .string(3, identity.mac)
        .string(4, ESPHOME_VERSION)
        .string(5, "VACA ${identity.appVersion}")
        .string(6, identity.model)
        .string(8, "msp1974.vaca")
        .string(9, identity.appVersion)
        .uint(11, 5)
        .string(12, identity.manufacturer)
        .string(13, identity.friendlyName)
        .uint(15, FEATURE_FLAGS)
        .string(18, identity.bluetoothMac)
        .toByteArray()

    fun rawAdvertisements(ads: List<BleAdvertisement>): ByteArray {
        val w = ProtoWriter()
        ads.forEach { ad ->
            w.message(
                1,
                ProtoWriter()
                    .uint(1, ad.address)
                    .sint32(2, ad.rssi)
                    .uint(3, ad.addressType)
                    .bytes(4, ad.data)
                    .toByteArray()
            )
        }
        return w.toByteArray()
    }

    fun scannerState(state: Int, activeMode: Boolean, configuredActive: Boolean): ByteArray = ProtoWriter()
        .uint(1, state)
        .uint(2, if (activeMode) 1 else 0)
        .uint(3, if (configuredActive) 1 else 0)
        .toByteArray()

    fun connectionsFree(free: Int, limit: Int, allocated: List<Long>): ByteArray = ProtoWriter()
        .uint(1, free)
        .uint(2, limit)
        .packedUInt64(3, allocated)
        .toByteArray()

    fun deviceConnection(address: Long, connected: Boolean, mtu: Int, error: Int): ByteArray = ProtoWriter()
        .uint(1, address)
        .bool(2, connected)
        .uint(3, mtu)
        .int32(4, error)
        .toByteArray()

    fun gattService(address: Long, service: GattServiceInfo): ByteArray {
        val svc = ProtoWriter()
            .packedUInt64(1, uuidWords(service.uuid))
            .uint(2, service.handle)
        service.characteristics.forEach { c ->
            val cw = ProtoWriter()
                .packedUInt64(1, uuidWords(c.uuid))
                .uint(2, c.handle)
                .uint(3, c.properties)
            c.descriptors.forEach { d ->
                cw.message(
                    4,
                    ProtoWriter().packedUInt64(1, uuidWords(d.uuid)).uint(2, d.handle).toByteArray()
                )
            }
            svc.message(3, cw.toByteArray())
        }
        return ProtoWriter()
            .uint(1, address)
            .message(2, svc.toByteArray())
            .toByteArray()
    }

    fun addressOnly(address: Long): ByteArray = ProtoWriter().uint(1, address).toByteArray()

    fun addressHandle(address: Long, handle: Int): ByteArray =
        ProtoWriter().uint(1, address).uint(2, handle).toByteArray()

    fun gattData(address: Long, handle: Int, data: ByteArray): ByteArray = ProtoWriter()
        .uint(1, address)
        .uint(2, handle)
        .bytes(3, data)
        .toByteArray()

    fun gattError(address: Long, handle: Int, error: Int): ByteArray = ProtoWriter()
        .uint(1, address)
        .uint(2, handle)
        .int32(3, error)
        .toByteArray()

    fun clearCacheResponse(address: Long, success: Boolean): ByteArray = ProtoWriter()
        .uint(1, address)
        .bool(2, success)
        .toByteArray()

    /** A UUID is sent as two 64-bit words, most significant first. */
    private fun uuidWords(uuid: java.util.UUID): List<Long> =
        listOf(uuid.mostSignificantBits, uuid.leastSignificantBits)
}

class GattDescriptorInfo(val uuid: java.util.UUID, val handle: Int)

class GattCharacteristicInfo(
    val uuid: java.util.UUID,
    val handle: Int,
    val properties: Int,
    val descriptors: List<GattDescriptorInfo>,
)

class GattServiceInfo(
    val uuid: java.util.UUID,
    val handle: Int,
    val characteristics: List<GattCharacteristicInfo>,
)

/** "AA:BB:CC:DD:EE:FF" <-> 48-bit integer as used on the wire. */
object BleAddress {
    fun toLong(address: String): Long = address.replace(":", "").toLong(16)

    fun toString(address: Long): String =
        (5 downTo 0).joinToString(":") { "%02X".format((address shr (it * 8)) and 0xFF) }
}
