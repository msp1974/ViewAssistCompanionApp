package com.msp1974.vacompanion.btproxy

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Helpers
import timber.log.Timber
import java.security.MessageDigest

/**
 * Owns the Home Assistant Bluetooth proxy for as long as the background task runs, independent of
 * whether a Wyoming/HA satellite session is connected. Starts and stops it as the
 * [com.msp1974.vacompanion.settings.APPConfig.bluetoothProxyEnabled] setting changes, following the
 * same config-event-driven pattern as [com.msp1974.vacompanion.sendspin.SendspinManager].
 *
 * The proxy speaks the ESPHome native API ([BtProxyApiServer]) and is discovered by Home
 * Assistant's ESPHome integration over mDNS ([BtProxyAdvertiser]). Advertisements are produced by
 * [BleScanner] and GATT requests are executed by [GattManager].
 */
class BluetoothProxyManager(
    private val context: Context,
    deviceManager: DeviceManager,
) : EventListener {

    private val config = deviceManager.config

    private var server: BtProxyApiServer? = null
    private var scanner: BleScanner? = null
    private var gatt: GattManager? = null
    private var advertiser: BtProxyAdvertiser? = null

    @Volatile private var configuredActive = false
    private var permissionReceiver: BroadcastReceiver? = null

    fun start() {
        config.eventBroadcaster.addListener(this)
        if (config.bluetoothProxyEnabled) startProxy()
    }

    fun shutdown() {
        config.eventBroadcaster.removeListener(this)
        unregisterPermissionReceiver()
        stopProxy()
    }

    @Synchronized
    private fun startProxy() {
        if (server != null) return

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Timber.w("Bluetooth proxy: this device has no Bluetooth LE support")
            return
        }
        if (!ensurePermissions()) return
        if (!adapter.isEnabled) {
            // Not fatal: the API still starts, scanning reports FAILED until Bluetooth is turned on.
            Timber.w("Bluetooth proxy: Bluetooth is turned off")
        }

        val identity = buildIdentity()

        val newServer = object : BtProxyApiServer(identity) {
            override fun onAdvertisementSubscribers(count: Int) {
                if (count > 0) scanner?.start(configuredActive) else scanner?.stop()
            }

            override fun onScannerModeRequested(active: Boolean) {
                configuredActive = active
                val s = scanner ?: return
                if (s.isScanning) s.start(active) else broadcastScannerState(scannerStatePayload())
            }

            override fun onScannerStateRequested(session: Session) {
                session.send(MsgId.BLE_SCANNER_STATE_RESPONSE, scannerStatePayload())
            }

            override fun onConnectionsFreeRequested(session: Session) {
                gatt?.let { session.send(MsgId.BLE_CONNECTIONS_FREE_RESPONSE, it.connectionsFreePayload()) }
            }

            override fun onGattMessage(session: Session, type: Int, fields: ProtoFields) {
                gatt?.handle(session, type, fields)
            }

            override fun onSessionClosed(session: Session) {
                gatt?.releaseSession(session)
            }

            override fun onLog(message: String) {
                Timber.d("Bluetooth proxy: $message")
            }
        }

        // Everything the server calls back into must exist before it accepts its first client.
        gatt = GattManager(context, adapter, newServer)
        scanner = object : BleScanner(adapter) {
            override fun onAdvertisements(ads: List<BleAdvertisement>) {
                newServer.sendAdvertisements(ads)
            }

            override fun onStateChanged() {
                newServer.broadcastScannerState(scannerStatePayload())
            }
        }
        if (!newServer.start(API_PORT)) {
            scanner?.shutdown()
            scanner = null
            gatt = null
            return
        }

        server = newServer
        advertiser = BtProxyAdvertiser(context, identity).also { it.start(API_PORT) }
        registerAdapterReceiver()
        Timber.i("Bluetooth proxy started as ${identity.nodeName}")
    }

    @Synchronized
    private fun stopProxy() {
        if (server == null) return
        unregisterAdapterReceiver()
        advertiser?.stop()
        advertiser = null
        scanner?.shutdown()
        scanner = null
        gatt?.shutdown()
        gatt = null
        server?.stop()
        server = null
        Timber.i("Bluetooth proxy stopped")
    }

    // --- Bluetooth adapter on/off ---

    private var adapterReceiver: BroadcastReceiver? = null

    // A running scan or GATT connection does not survive the Bluetooth stack being switched off
    // (or restarting), and nothing tells the scanner - so follow the adapter state explicitly.
    private fun registerAdapterReceiver() {
        if (adapterReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Timber.i("Bluetooth proxy: Bluetooth turned off, stopping scan")
                        scanner?.stop()
                        gatt?.adapterLost()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        val wanted = server?.advertisementSubscriberCount ?: 0
                        Timber.i("Bluetooth proxy: Bluetooth turned on, $wanted client(s) want advertisements")
                        if (wanted > 0) scanner?.start(configuredActive)
                    }
                }
            }
        }
        adapterReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterAdapterReceiver() {
        adapterReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
            adapterReceiver = null
        }
    }

    private fun scannerStatePayload(): ByteArray {
        val s = scanner
        return ApiMessages.scannerState(
            s?.state ?: ScannerState.STOPPED,
            s?.activeMode ?: configuredActive,
            configuredActive,
        )
    }

    // --- Identity ---

    private fun buildIdentity(): ProxyIdentity {
        val suffix = config.uuid.filter { it.isLetterOrDigit() }.lowercase().take(8)
        val deviceName = Helpers.getDeviceName() ?: Build.MODEL
        // Home Assistant has no way to read this device's real Bluetooth address (Android hides
        // it), so derive a stable, locally administered one from the app's UUID. It is only used
        // to identify this proxy in Home Assistant.
        val hash = MessageDigest.getInstance("SHA-256").digest("vaca-btproxy-${config.uuid}".toByteArray())
        val mac = ByteArray(6) { hash[it] }
        mac[0] = ((mac[0].toInt() or 0x02) and 0xFE).toByte()
        val btMac = mac.copyOf().also { it[5] = (it[5] + 1).toByte() }
        return ProxyIdentity(
            nodeName = "vaca-btproxy-$suffix",
            friendlyName = "${config.name} $deviceName Bluetooth Proxy",
            mac = formatMac(mac),
            bluetoothMac = formatMac(btMac),
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            appVersion = appVersion(),
        )
    }

    private fun formatMac(bytes: ByteArray) = bytes.joinToString(":") { "%02X".format(it) }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }

    // --- Permissions ---

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * True if everything needed is granted. Otherwise asks MainActivity to request the next
     * missing permission (it owns the activity-result launchers) and retries once granted.
     */
    private fun ensurePermissions(): Boolean {
        val missing = requiredPermissions().firstOrNull {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        } ?: return true

        Timber.w("Bluetooth proxy: $missing not granted, requesting...")
        registerPermissionReceiver()
        BroadcastSender.sendBroadcast(context, BroadcastSender.OPEN_PERMISSION_SCREEN, missing)
        return false
    }

    private fun registerPermissionReceiver() {
        if (permissionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getStringExtra("extra") in requiredPermissions()) {
                    Timber.d("Bluetooth proxy: permission granted, retrying start")
                    unregisterPermissionReceiver()
                    if (config.bluetoothProxyEnabled) startProxy()
                }
            }
        }
        permissionReceiver = receiver
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, IntentFilter(BroadcastSender.PERMISSION_GRANTED))
    }

    private fun unregisterPermissionReceiver() {
        permissionReceiver?.let {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(it)
            permissionReceiver = null
        }
    }

    // The setting is SharedPreferences-backed, so the event carries no typed value - read it back.
    override fun onEventTriggered(event: Event) {
        if (event.eventName != "bluetooth_proxy_enabled") return
        Timber.d("BluetoothProxyManager: bluetooth_proxy_enabled=${config.bluetoothProxyEnabled}")
        if (config.bluetoothProxyEnabled) startProxy() else {
            unregisterPermissionReceiver()
            stopProxy()
        }
    }

    companion object {
        // The standard ESPHome native API port.
        const val API_PORT = 6053
    }
}
