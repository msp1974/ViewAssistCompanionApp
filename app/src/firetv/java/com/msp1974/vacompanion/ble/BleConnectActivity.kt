package com.msp1974.vacompanion.ble

import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.ui.layouts.BleConnectScreen
import com.msp1974.vacompanion.ui.layouts.BleConnectUiState
import com.msp1974.vacompanion.ui.layouts.BleDeviceRow
import com.msp1974.vacompanion.ui.theme.AppTheme

/**
 * BLE connect prompt activity.
 *
 * Phase 1: Scan button reflects state: "SCANNING" vs "SCAN".
 * Phase 2: Auto-dismiss countdown (5s if last remote exists, else 20s) shown on Skip.
 * Phase 3: "Forget All" button clears persisted last-remote and also instructs the service.
 *
 * Connection Gate: While this activity is shown, we gate connected=true broadcasts from the
 * service so the user can choose to connect a new remote or forget all. The gate is released
 * only when the timeout finishes or the user takes an explicit action.
 *
 * Notes:
 * - On SKIP/TIMEOUT we broadcast a deterministic state so MainActivity can advance.
 * - We DO NOT auto-select the first discovered device; the USER must pick from the list.
 * - On explicit user selection we:
 *      (1) tell the service to listen to that MAC,
 *      (2) release the broadcast gate,
 *      (3) emit a "listening_selected" reason so MainActivity can proceed immediately,
 *          even if no further advert has arrived yet.
 */
class BleConnectActivity : ComponentActivity() {

    // -------------------------------------------------------------------------
    // UI state (Compose-observed)
    // -------------------------------------------------------------------------

    private var uiState by mutableStateOf(BleConnectUiState())

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private val devices = mutableListOf<BleDeviceRow>()

    private var countdownRunning = false
    private var countdownMillisTotal: Long = 0L
    private var countdownStartedAt: Long = 0L
    private var hasLastRemote = false

    private val ui = Handler(Looper.getMainLooper())

    // -------------------------------------------------------------------------
    // Countdown ticker
    // -------------------------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            if (!countdownRunning) return
            val elapsed = System.currentTimeMillis() - countdownStartedAt
            val remain  = (countdownMillisTotal - elapsed).coerceAtLeast(0L)
            val secs    = ((remain + 999) / 1000).toInt()

            uiState = uiState.copy(countdownSecs = secs)

            if (remain == 0L) {
                sendGate(false)
                LocalBroadcastManager.getInstance(this@BleConnectActivity).sendBroadcast(
                    Intent(BleRemoteService.ACTION_CONNECTION_STATE)
                        .putExtra(BleRemoteService.EXTRA_CONNECTED, false)
                        .putExtra(BleRemoteService.EXTRA_REASON, BleRemoteService.REASON_TIMEOUT)
                )
                setResult(RESULT_OK, Intent().putExtra("ble_skipped_timeout", true))
                finish()
                return
            }
            ui.postDelayed(this, 250L)
        }
    }

    // -------------------------------------------------------------------------
    // Broadcast receiver
    // -------------------------------------------------------------------------

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            when (i.action) {
                BleRemoteService.ACTION_SCAN_RESULT -> {
                    val name = i.getStringExtra(BleRemoteService.EXTRA_NAME) ?: "(unknown)"
                    val mac  = i.getStringExtra(BleRemoteService.EXTRA_MAC) ?: return
                    val rssi = i.getIntExtra(BleRemoteService.EXTRA_RSSI, 0)
                    upsertDevice(BleDeviceRow(name, mac, rssi))
                    uiState = uiState.copy(scanning = true, devices = devices.toList())
                }
                BleRemoteService.ACTION_SCAN_DONE -> {
                    uiState = uiState.copy(scanning = false)
                }
                BleRemoteService.ACTION_CONNECTION_STATE -> {
                    val connected = i.getBooleanExtra(BleRemoteService.EXTRA_CONNECTED, false)
                    val reason    = i.getStringExtra(BleRemoteService.EXTRA_REASON)

                    when {
                        connected -> {
                            // Service confirmed the remote is live — dismiss immediately.
                            cancelCountdown()
                            markPromptHandled(bleDisabled = false)
                            setResult(RESULT_OK, Intent().putExtra("ble_connected", true))
                            finish()
                        }
                        reason == BleRemoteService.REASON_LISTENING -> {
                            // Service has auto-selected the last remote and is listening for
                            // its advert. Show this in the UI and give the user extra time
                            // (15s) so they can intervene if they want a different device —
                            // but we don't force-dismiss; they'll be taken through naturally
                            // once connected=true arrives or the extended countdown expires.
                            val name = i.getStringExtra(BleRemoteService.EXTRA_NAME)
                            uiState = uiState.copy(
                                reconnecting    = true,
                                lastRemoteName  = name ?: uiState.lastRemoteName,
                            )
                            // Reset to a generous 15s so the user can actually read the screen.
                            startCountdown(15_000L)
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme(dynamicColor = false, darkMode = false) {
                BleConnectScreen(
                    uiState        = uiState,
                    onScan         = ::startScan,
                    onSkip         = ::onSkip,
                    onForget       = ::onForget,
                    onDeviceSelect = ::onDeviceSelected,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()

        sendGate(true)

        val prefs = getSharedPreferences("vaca_prefs", MODE_PRIVATE)
        val lastMac  = prefs.getString("last_remote_mac", null)
        val lastName = prefs.getString("last_remote_name", null)
        hasLastRemote = !lastMac.isNullOrBlank()
        uiState = uiState.copy(hasLastRemote = hasLastRemote, lastRemoteName = lastName)

        val filter = IntentFilter().apply {
            addAction(BleRemoteService.ACTION_SCAN_RESULT)
            addAction(BleRemoteService.ACTION_SCAN_DONE)
            addAction(BleRemoteService.ACTION_CONNECTION_STATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(rx, filter)

        startScan()
        // Delay the initial countdown start by 500ms so Compose has a chance to
        // paint its first frame before we begin burning down.
        // If reason=listening arrives before this fires (auto-reconnect case),
        // the receiver overrides with a 15s countdown anyway.
        ui.postDelayed(::startCountdownForPresenceOfLastRemote, 500L)
    }

    override fun onStop() {
        ui.removeCallbacks(::startCountdownForPresenceOfLastRemote)
        cancelCountdown()
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(rx) } catch (_: Throwable) { }
        super.onStop()
    }

    // -------------------------------------------------------------------------
    // Button actions
    // -------------------------------------------------------------------------

    private fun onSkip() {
        sendGate(false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(BleRemoteService.ACTION_CONNECTION_STATE)
                .putExtra(BleRemoteService.EXTRA_CONNECTED, false)
                .putExtra(BleRemoteService.EXTRA_REASON, BleRemoteService.REASON_SKIPPED)
        )
        markPromptHandled(bleDisabled = true)
        setResult(RESULT_OK, Intent().putExtra("ble_disabled", true))
        finish()
    }

    private fun onForget() {
        clearLastRemotePrefs()
        startForegroundService(Intent(this, BleRemoteService::class.java).apply {
            action = BleRemoteService.ACTION_DISCONNECT
            putExtra(BleRemoteService.EXTRA_FORGET, true)
        })
        sendGate(false)
        markPromptHandled(bleDisabled = false)
        setResult(RESULT_OK, Intent().putExtra("ble_forget_all", true))
        finish()
    }

    private fun onDeviceSelected(row: BleDeviceRow) {
        cancelCountdown()
        // Skip the selection state and immediately connect
        connectToMac(row.mac)
    }

    private fun connectToMac(mac: String) {
        val row = devices.firstOrNull { it.mac == mac } ?: return

        getSharedPreferences("vaca_prefs", MODE_PRIVATE).edit()
            .putString("last_remote_mac", row.mac)
            .putString("last_remote_name", row.name)
            .putLong("last_remote_seen", System.currentTimeMillis())
            .apply()

        startForegroundService(Intent(this, BleRemoteService::class.java).apply {
            action = BleRemoteService.ACTION_CONNECT_MAC
            putExtra(BleRemoteService.EXTRA_MAC, row.mac)
            putExtra(BleRemoteService.EXTRA_NAME, row.name)
        })

        sendGate(false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(BleRemoteService.ACTION_CONNECTION_STATE)
                .putExtra(BleRemoteService.EXTRA_CONNECTED, false)
                .putExtra(BleRemoteService.EXTRA_REASON, BleRemoteService.REASON_LISTENING_SELECTED)
                .putExtra(BleRemoteService.EXTRA_MAC, row.mac)
                .putExtra(BleRemoteService.EXTRA_NAME, row.name)
        )

        markPromptHandled(bleDisabled = false)
        setResult(RESULT_OK, Intent().putExtra("ble_mac", row.mac))
        finish()
    }

    // -------------------------------------------------------------------------
    // Scan helpers
    // -------------------------------------------------------------------------

    private fun startScan() {
        startForegroundService(Intent(this, BleRemoteService::class.java).apply {
            action = BleRemoteService.ACTION_START_SCAN
        })
        uiState = uiState.copy(scanning = true)
    }

    // -------------------------------------------------------------------------
    // Device list helpers
    // -------------------------------------------------------------------------

    private fun upsertDevice(row: BleDeviceRow) {
        val idx = devices.indexOfFirst { it.mac == row.mac }
        if (idx >= 0) devices[idx] = row else devices += row
    }

    // -------------------------------------------------------------------------
    // Countdown
    // -------------------------------------------------------------------------

    private fun startCountdownForPresenceOfLastRemote() {
        // 15s when reconnecting to a known device (gives user time to intervene),
        // 20s when no prior device is known (needs more time to scan and pick one).
        startCountdown(if (hasLastRemote) 15_000L else 20_000L)
    }

    private fun startCountdown(millis: Long) {
        cancelCountdown()
        countdownMillisTotal = millis
        countdownStartedAt  = System.currentTimeMillis()
        countdownRunning    = true
        ui.post(tick)
        uiState = uiState.copy(countdownSecs = ((millis + 999) / 1000).toInt())
    }

    private fun cancelCountdown() {
        if (!countdownRunning) return
        countdownRunning = false
        ui.removeCallbacks(tick)
        uiState = uiState.copy(countdownSecs = null)
    }

    // -------------------------------------------------------------------------
    // Prefs / service helpers
    // -------------------------------------------------------------------------

    private fun sendGate(enable: Boolean) {
        startForegroundService(Intent(this, BleRemoteService::class.java).apply {
            action = BleRemoteService.ACTION_SET_CONN_GATE
            putExtra(BleRemoteService.EXTRA_GATE, enable)
        })
    }

    private fun markPromptHandled(bleDisabled: Boolean) {
        getSharedPreferences("vaca_prefs", MODE_PRIVATE).edit()
            .putBoolean("ble_prompt_done", true)
            .putBoolean("ble_disabled", bleDisabled)
            .apply()
    }

    private fun clearLastRemotePrefs() {
        getSharedPreferences("vaca_prefs", MODE_PRIVATE).edit()
            .remove("last_remote_mac")
            .remove("last_remote_name")
            .remove("last_remote_seen")
            .apply()
    }
}