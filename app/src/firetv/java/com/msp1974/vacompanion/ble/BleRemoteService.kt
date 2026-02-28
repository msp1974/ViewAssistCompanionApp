package com.msp1974.vacompanion.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.BuildConfig
import com.msp1974.vacompanion.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Scan-only BLE listener for non-connectable adverts from the remote.
 *
 * Manufacturer Specific Data company ID: 0x02E5 (Espressif).
 * Payload (company ID is stripped by ScanRecord API):
 * [0] ver   (0x01)
 * [1] seq   (0..255)
 * [2] key   (0x10 prev, 0x11 next, 0x12 null, 0x13 select)
 * [3] batt% (0..100; 0xFF if unknown)
 * [4] status0 (bitfield: b0..b2 resetCause, b5..b7 txPowerIdx)
 * [5] status1 (brownoutCount 0..255)
 *
 * Conventions:
 * - ACTION_CONNECTION_STATE always has EXTRA_REASON:
 * "connected" | "disconnected" | "skipped" | "timeout" | "listening" | "listening_selected".
 * - We treat ANY advert from the selected MAC as presence → emit connected=true even if MSD is missing.
 */
class BleRemoteService : Service() {

    companion object {
        private const val NS = "${BuildConfig.APPLICATION_ID}.ble"

        // Requests from UI
        const val ACTION_START_SCAN   = "$NS.ACTION_START_SCAN"
        const val ACTION_STOP_SCAN    = "$NS.ACTION_STOP_SCAN"
        const val ACTION_DISCONNECT   = "$NS.ACTION_DISCONNECT"
        const val ACTION_CONNECT_MAC  = "$NS.ACTION_CONNECT_MAC"

        // Gate connection broadcasts until UI allows them
        const val ACTION_SET_CONN_GATE = "$NS.ACTION_SET_CONN_GATE"
        const val EXTRA_GATE           = "gate"

        // Scan results to UI
        const val ACTION_SCAN_RESULT  = "$NS.ACTION_SCAN_RESULT"
        const val ACTION_SCAN_DONE    = "$NS.ACTION_SCAN_DONE"

        // State + button events
        const val ACTION_CONNECTION_STATE = "$NS.ACTION_CONNECTION_STATE"
        const val ACTION_BUTTON = "$NS.ACTION_BUTTON"

        // Common extras
        const val EXTRA_KEY     = "key"
        const val EXTRA_NAME      = "name"
        const val EXTRA_MAC       = "mac"
        const val EXTRA_RSSI      = "rssi"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_FORGET    = "forget"

        // Reasons
        const val EXTRA_REASON               = "reason"
        const val REASON_CONNECTED           = "connected"
        const val REASON_DISCONNECTED        = "disconnected"
        const val REASON_SKIPPED             = "skipped"
        const val REASON_TIMEOUT             = "timeout"
        const val REASON_LISTENING           = "listening"
        const val REASON_LISTENING_SELECTED  = "listening_selected" // emitted by Activity on user pick

        // Battery / status extras
        const val EXTRA_BATTERY_PCT   = "battery_pct"
        const val EXTRA_RESET_CAUSE   = "reset_cause"
        const val EXTRA_BROWNOUT_CNT  = "brownout_cnt"
        const val EXTRA_TX_POWER_IDX  = "txp_idx"

        // Internals
        private const val NOTIF_CHAN_ID = "ble_remote_foreground"
        private const val NOTIF_ID = 101

        private const val COMPANY_ID_ESPRESSIF = 0x02E5
        private const val DEDUP_WINDOW_MS = 120L
        private const val RSSI_HOLD_MS   = 1000L
        private const val SCAN_WINDOW_MS = 30_000L

        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val HEARTBEAT_MS = 1_000L

        private const val TAG_RX = "ADVERT_RX"
        private const val TAG_SVC = "BLE_SVC"
    }

    // Android / BLE
    private var bt: BluetoothAdapter? = null
    private val lbm by lazy { LocalBroadcastManager.getInstance(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Scan state
    @Volatile private var scanning = false
    private var scanner: BluetoothLeScanner? = null
    private var rssiMax = Int.MIN_VALUE
    private var rssiLastTs = 0L

    private val seenMacs = mutableSetOf<String>()
    private val lastSeenSeq: MutableMap<String, Pair<Int, Long>> = ConcurrentHashMap()
    private val lastDeliveredSeq: MutableMap<String, Int> = ConcurrentHashMap()

    // Selection + connection state
    private var selectedMac: String? = null
    private var selectedName: String? = null
    private var lastSelectedSeenTs: Long = 0L
    private var uiConnectedEmitted = false

    private var gateConnections = false
    private var pendingConnectedWhileGated = false

    // Heartbeat to assert/disconnect by silence
    private val heartbeat = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val mac = selectedMac
            if (mac != null) {
                val everSeen = lastSelectedSeenTs > 0L
                if (everSeen) {
                    val silentTooLong = (now - lastSelectedSeenTs) > CONNECT_TIMEOUT_MS
                    if (silentTooLong) {
                        if (uiConnectedEmitted) {
                            uiConnectedEmitted = false
                            Log.i(TAG_RX, "TIMEOUT: no adverts from selected MAC $mac for ${CONNECT_TIMEOUT_MS}ms -> UI disconnected")
                            doBroadcastState(false, selectedName, selectedMac, currentRssi())

                            // Forget sequences on timeout so the next wake-up isn't incorrectly rejected
                            lastDeliveredSeq.remove(mac)
                            lastSeenSeq.remove(mac)
                        }
                    } else if (!uiConnectedEmitted) {
                        if (!gateConnections) {
                            uiConnectedEmitted = true
                            Log.d(TAG_RX, "HEARTBEAT: asserting connected for MAC $mac (gate open)")
                            doBroadcastState(true, selectedName, selectedMac, currentRssi())
                        } else {
                            pendingConnectedWhileGated = true
                            Log.d(TAG_RX, "HEARTBEAT: connected observed but gated; deferring broadcast")
                        }
                    }
                } else {
                    updateNotification("Listening… (${selectedName ?: "-"})")
                }
            }
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bt = getSystemService<BluetoothManager>()?.adapter
        createNotificationChannelIfNeeded()
        startForeground(NOTIF_ID, buildNotification("Idle"))
        mainHandler.post(heartbeat)
        Log.i(TAG_SVC, "BleRemoteService created")
    }

    override fun onDestroy() {
        try { mainHandler.removeCallbacks(heartbeat) } catch (_: Throwable) { }
        stopScanInternal()
        Log.i(TAG_SVC, "BleRemoteService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> {
                Log.i(TAG_SVC, "ACTION_START_SCAN")
                if (selectedMac == null) {
                    seenMacs.clear()
                    lastSeenSeq.clear()
                    lastDeliveredSeq.clear()

                    if (tryAutoSelectLastRemote()) {
                        startScan()
                        // Inform UI we’re listening to a remembered remote (no auto-advance)
                        doBroadcastState(false, selectedName, selectedMac, currentRssi(), REASON_LISTENING)
                    } else {
                        startScan()
                        scheduleScanStop(SCAN_WINDOW_MS)
                    }
                } else {
                    startScan()
                }
            }
            ACTION_STOP_SCAN -> {
                Log.i(TAG_SVC, "ACTION_STOP_SCAN")
                if (selectedMac != null) {
                    Log.i(TAG_SVC, "Ignoring STOP while listening to $selectedMac")
                } else {
                    stopScanInternal()
                    lbm.sendBroadcast(Intent(ACTION_SCAN_DONE))
                }
            }
            ACTION_DISCONNECT -> {
                val forget = intent.getBooleanExtra(EXTRA_FORGET, false)
                Log.i(TAG_SVC, "ACTION_DISCONNECT forget=$forget")
                if (forget) {
                    lastSeenSeq.clear()
                    lastDeliveredSeq.clear()
                    seenMacs.clear()
                    clearLastRemotePrefs()
                }
                selectedMac = null
                selectedName = null
                lastSelectedSeenTs = 0L
                uiConnectedEmitted = false
                pendingConnectedWhileGated = false
                stopScanInternal()
                doBroadcastState(false, null, null, null)
            }
            ACTION_CONNECT_MAC -> {
                selectedMac  = intent.getStringExtra(EXTRA_MAC)
                selectedName = intent.getStringExtra(EXTRA_NAME) ?: "Advert listener"
                lastSelectedSeenTs = 0L
                uiConnectedEmitted = false
                pendingConnectedWhileGated = false
                updateNotification("Listening… (${selectedName ?: "-"})")
                Log.i(TAG_SVC, "ACTION_CONNECT_MAC mac=$selectedMac name=$selectedName")

                // Reset per-device delivery guard when switching targets
                lastDeliveredSeq.remove(selectedMac)

                startScan()
                // No immediate broadcast here; the Activity has already sent "listening_selected".
                // We will emit connected=true on the first presence advert from this MAC (MSD or not).
            }
            ACTION_SET_CONN_GATE -> {
                val gate = intent.getBooleanExtra(EXTRA_GATE, false)
                Log.i(TAG_SVC, "ACTION_SET_CONN_GATE gate=$gate")
                gateConnections = gate
                if (!gateConnections) {
                    // Gate just opened; emit pending connected if we saw it while gated
                    if (pendingConnectedWhileGated && selectedMac != null && lastSelectedSeenTs > 0L) {
                        pendingConnectedWhileGated = false
                        if (!uiConnectedEmitted) uiConnectedEmitted = true
                        doBroadcastState(true, selectedName, selectedMac, currentRssi())
                        Log.i(TAG_RX, "GATE OPEN: emitted deferred connected=true for ${selectedMac}")
                    }
                } else {
                    pendingConnectedWhileGated = false
                }
            }
        }
        return START_STICKY
    }

    // --- Scanning ---

    private fun startScan() {
        if (!hasScanPermission()) {
            updateNotification("Permission required")
            Log.w(TAG_SVC, "startScan() denied: missing permission")
            return
        }

        val adapter = bt
        if (adapter == null || !adapter.isEnabled) {
            updateNotification("Bluetooth off")
            Log.w(TAG_SVC, "startScan() denied: adapter null or disabled")
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            updateNotification("No BLE scanner")
            Log.e(TAG_SVC, "startScan() failed: bluetoothLeScanner=null")
            return
        }
        this.scanner = scanner

        val filters = mutableListOf<ScanFilter>()

        // Discovery filter for ESP MSD so we only list remotes (ACTION_SCAN_RESULT)
        filters += ScanFilter.Builder()
            .setManufacturerData(COMPANY_ID_ESPRESSIF, byteArrayOf())
            .build()

        // When a MAC is selected, ALSO filter by device address so we see ANY advert from it,
        // even if the packet doesn't carry MSD (some firmwares only include MSD on key events).
        selectedMac?.let { mac ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                filters += ScanFilter.Builder().setDeviceAddress(mac).build()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        if (scanning) {
            Log.i(TAG_SVC, "startScan(): already scanning; restarting with updated filters")
            try { this.scanner?.stopScan(scanCb) } catch (_: Throwable) {}
        }

        scanning = true
        if (selectedMac == null) {
            rssiMax = Int.MIN_VALUE
            rssiLastTs = 0L
        }

        try {
            this.scanner!!.startScan(filters, settings, scanCb)
            updateNotification(if (selectedMac == null) "Scanning…" else "Listening… (${selectedName ?: "-"})")
            Log.i(TAG_SVC, "Scanning started (${if (selectedMac == null) "discovery" else "listening to ${selectedMac}"})")
        } catch (t: Throwable) {
            updateNotification("Scan start failed")
            scanning = false
            Log.e(TAG_SVC, "startScan() exception", t)
        }
    }

    private fun scheduleScanStop(delayMs: Long) {
        mainHandler.postDelayed({
            if (!scanning) return@postDelayed
            if (selectedMac != null) return@postDelayed
            stopScanInternal()
            lbm.sendBroadcast(Intent(ACTION_SCAN_DONE))
            updateNotification("Scan finished")
            Log.i(TAG_SVC, "Scanning stopped (scheduled after ${delayMs}ms)")
        }, delayMs)
    }

    private fun stopScanInternal() {
        try { scanner?.stopScan(scanCb) } catch (_: Throwable) {}
        scanning = false
        updateNotification(if (selectedMac == null) "Idle" else "Listening… (${selectedName ?: "-"})")
        Log.i(TAG_SVC, "stopScanInternal()")
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::handleResult) }
        override fun onScanFailed(errorCode: Int) {
            updateNotification("Scan failed ($errorCode)")
            Log.e(TAG_SVC, "onScanFailed($errorCode)")
        }
    }

    private fun keyToString(key: Int): String = when (key) {
        0x10 -> "prev"
        0x11 -> "next"
        0x12 -> "null"
        0x13 -> "select"
        0x14 -> "double_tap"
        0x15 -> "long_press"
        0x16 -> "swipe_up"
        0x17 -> "swipe_down"
        0x18 -> "swipe_left"
        0x19 -> "swipe_right"
        else -> "0x${key.toString(16)}"
    }

    private fun handleResult(res: ScanResult) {
        val now = System.currentTimeMillis()
        val mac = res.device?.address ?: return
        val rssi = res.rssi
        val rec = res.scanRecord ?: run {
            Log.d(TAG_RX, "RX mac=$mac rssi=$rssi ignored: no scanRecord")
            return
        }

        val selMac = selectedMac
        val md = rec.getManufacturerSpecificData(COMPANY_ID_ESPRESSIF)

        // 1. Discovery Path: Always broadcast found ESP devices to the UI,
        // even if we are actively listening to a specific remote.
        if (md != null) {
            if (seenMacs.add(mac)) {
                val name = res.device?.name ?: rec.deviceName ?: "Remote"
                lbm.sendBroadcast(
                    Intent(ACTION_SCAN_RESULT)
                        .putExtra(EXTRA_NAME, name)
                        .putExtra(EXTRA_MAC, mac)
                        .putExtra(EXTRA_RSSI, rssi)
                )
                Log.i(TAG_RX, "DISCOVER mac=$mac name='${name}' rssi=$rssi")
            }
        }

        // If no MAC is actively selected for listening, we just track generic RSSI and bail.
        if (selMac == null) {
            updateRssi(rssi, now)
            return
        }

        // 2. Listening Path: Only process presence and buttons for the selected MAC.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && mac != selMac) return
        if (mac != selMac) return

        // Presence on ANY advert from selected MAC (even if MSD is missing)
        lastSelectedSeenTs = now
        updateRssi(rssi, now)

        if (!uiConnectedEmitted) {
            if (!gateConnections) {
                uiConnectedEmitted = true
                doBroadcastState(true, selectedName, selectedMac, currentRssi())
            } else {
                pendingConnectedWhileGated = true
                Log.d(TAG_RX, "Presence observed; connected pending but gated")
            }
        }

        // If no MSD, we’re done (we already asserted connected=true)
        if (md == null || md.size < 3) {
            Log.d(TAG_RX, "RX mac=$mac rssi=$rssi presence-only (no/short MSD) → treated as connected")
            return
        }

        val seq  = (md.getOrNull(1) ?: 0).toInt() and 0xFF
        val key  = (md.getOrNull(2) ?: 0).toInt() and 0xFF
        val battPct = (md.getOrNull(3) ?: 0xFF).toInt() and 0xFF
        val status0 = (md.getOrNull(4) ?: 0x00).toInt() and 0xFF
        val status1 = (md.getOrNull(5) ?: 0x00).toInt() and 0xFF
        val resetCause = status0 and 0x07
        val txpIdx     = (status0 ushr 5) and 0x07
        val brownouts  = status1 and 0xFF
        val keyStr = keyToString(key)

        // Duplicate suppression
        lastSeenSeq[mac]?.let { (lastSeq, ts) ->
            if (lastSeq == seq && (now - ts) <= DEDUP_WINDOW_MS) {
                if (!uiConnectedEmitted) {
                    if (!gateConnections) {
                        uiConnectedEmitted = true
                        doBroadcastState(true, selectedName, selectedMac, currentRssi())
                    } else {
                        pendingConnectedWhileGated = true
                    }
                }
                Log.d(TAG_RX, "RX mac=$mac rssi=$rssi seq=$seq key=$keyStr DUP within ${now - ts}ms")
                return
            }
        }
        lastSeenSeq[mac] = seq to now

        lastDeliveredSeq[mac]?.let { lastDelivered ->
            if (seq == lastDelivered) {
                if (!uiConnectedEmitted) {
                    if (!gateConnections) {
                        uiConnectedEmitted = true
                        doBroadcastState(true, selectedName, selectedMac, currentRssi())
                    } else {
                        pendingConnectedWhileGated = true
                    }
                }
                Log.d(TAG_RX, "RX mac=$mac rssi=$rssi seq=$seq key=$keyStr ALREADY_DELIVERED")
                return
            }
        }
        lastDeliveredSeq[mac] = seq

        if (!uiConnectedEmitted) {
            if (!gateConnections) {
                uiConnectedEmitted = true
                doBroadcastState(true, selectedName, selectedMac, currentRssi())
            } else {
                pendingConnectedWhileGated = true
                Log.d(TAG_RX, "ACCEPTED first packet; connected pending but gated")
            }
        }

        Log.i(TAG_RX, "RX mac=$mac rssi=$rssi seq=$seq key=$keyStr ACCEPTED batt=${if (battPct in 0..100) battPct else -1}% rc=$resetCause bo=$brownouts txp=$txpIdx")

        when (key) {
            0x10 -> sendButton("prev", res, battPct, resetCause, brownouts, txpIdx)
            0x11 -> sendButton("next", res, battPct, resetCause, brownouts, txpIdx)
            0x12 -> sendButton("null", res, battPct, resetCause, brownouts, txpIdx)
            0x13 -> sendButton("select", res, battPct, resetCause, brownouts, txpIdx)
            0x14 -> sendButton("double_tap", res, battPct, resetCause, brownouts, txpIdx)
            0x15 -> sendButton("long_press", res, battPct, resetCause, brownouts, txpIdx)
            0x16 -> sendButton("swipe_up", res, battPct, resetCause, brownouts, txpIdx)
            0x17 -> sendButton("swipe_down", res, battPct, resetCause, brownouts, txpIdx)
            0x18 -> sendButton("swipe_left", res, battPct, resetCause, brownouts, txpIdx)
            0x19 -> sendButton("swipe_right", res, battPct, resetCause, brownouts, txpIdx)
        }
    }

    private fun sendButton(
        kind: String,
        res: ScanResult,
        battPct: Int,
        resetCause: Int,
        brownouts: Int,
        txpIdx: Int
    ) {
        val mac = res.device?.address ?: "-"
        val i = Intent(ACTION_BUTTON)
            .putExtra(EXTRA_KEY, kind)
            .putExtra(EXTRA_MAC, mac)

        if (battPct in 0..100) i.putExtra(EXTRA_BATTERY_PCT, battPct)
        i.putExtra(EXTRA_RESET_CAUSE, resetCause)
        i.putExtra(EXTRA_BROWNOUT_CNT, brownouts)
        i.putExtra(EXTRA_TX_POWER_IDX, txpIdx)

        lbm.sendBroadcast(i)
        Log.i(TAG_RX, "BUTTON mac=$mac key=$kind" + (if (battPct in 0..100) " batt=${battPct}%" else "") + " rc=$resetCause bo=$brownouts txp=$txpIdx")
    }

    private fun updateRssi(rssi: Int, now: Long) {
        if (now - rssiLastTs > RSSI_HOLD_MS) {
            rssiMax = rssi
            rssiLastTs = now
        } else {
            rssiMax = max(rssiMax, rssi)
        }
    }

    private fun currentRssi(): Int? = if (rssiMax == Int.MIN_VALUE) null else rssiMax

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService<NotificationManager>() ?: return
            val chan = NotificationChannel(NOTIF_CHAN_ID, "BLE Remote", NotificationManager.IMPORTANCE_LOW).apply {
                description = "BLE scanner"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIF_CHAN_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService<NotificationManager>() ?: return
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun doBroadcastState(
        connected: Boolean,
        name: String?,
        mac: String?,
        rssi: Int?,
        reason: String? = null
    ) {
        val normalizedReason = reason ?: if (connected) REASON_CONNECTED else REASON_DISCONNECTED
        val i = Intent(ACTION_CONNECTION_STATE)
            .putExtra(EXTRA_CONNECTED, connected)
            .putExtra(EXTRA_REASON, normalizedReason)
        name?.let { i.putExtra(EXTRA_NAME, it) }
        mac?.let { i.putExtra(EXTRA_MAC, it) }
        rssi?.let { i.putExtra(EXTRA_RSSI, it) }
        lbm.sendBroadcast(i)
        Log.i(TAG_RX, "STATE connected=$connected reason=$normalizedReason name='${name ?: "-"}' mac=${mac ?: "-"} rssi=${rssi ?: "-"}")

        if (connected && !mac.isNullOrBlank()) {
            try {
                val prefs = getSharedPreferences("vaca_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putString("last_remote_mac", mac)
                    .putString("last_remote_name", name)
                    .putLong("last_remote_seen", System.currentTimeMillis())
                    .apply()
            } catch (t: Throwable) {
                Log.w(TAG_SVC, "Persist last remote failed: ${t.message}")
            }
        }
    }

    private fun tryAutoSelectLastRemote(): Boolean {
        val prefs = getSharedPreferences("vaca_prefs", MODE_PRIVATE)
        val mac = prefs.getString("last_remote_mac", null)?.takeIf { it.isNotBlank() } ?: return false
        val name = prefs.getString("last_remote_name", null) ?: "Advert listener"
        selectedMac = mac
        selectedName = name
        lastSelectedSeenTs = 0L
        uiConnectedEmitted = false
        pendingConnectedWhileGated = false
        updateNotification("Listening… (${selectedName ?: "-"})")
        Log.i(TAG_SVC, "AUTO-CONNECT: selected last remote mac=$mac name=$name")
        lastDeliveredSeq.remove(selectedMac)
        return true
    }

    private fun clearLastRemotePrefs() {
        try {
            val prefs = getSharedPreferences("vaca_prefs", MODE_PRIVATE)
            prefs.edit()
                .remove("last_remote_mac")
                .remove("last_remote_name")
                .remove("last_remote_seen")
                .apply()
            Log.i(TAG_SVC, "Cleared persisted last-remote keys")
        } catch (t: Throwable) {
            Log.w(TAG_SVC, "Failed clearing persisted last-remote keys: ${t.message}")
        }
    }
}