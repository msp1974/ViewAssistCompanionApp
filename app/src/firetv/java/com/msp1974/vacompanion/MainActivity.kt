package com.msp1974.vacompanion

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.UiModeManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.ExperimentalMirrorMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.service.VAForegroundService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.BackgroundTaskStatus
import com.msp1974.vacompanion.ui.VADialog
import com.msp1974.vacompanion.ui.components.VADialog
import com.msp1974.vacompanion.ui.layouts.BlackScreen
import com.msp1974.vacompanion.ui.layouts.ConnectionScreen
import com.msp1974.vacompanion.ui.layouts.WebViewScreen
import com.msp1974.vacompanion.ui.theme.AppTheme
import com.msp1974.vacompanion.utils.AuthUtils
import com.msp1974.vacompanion.utils.CustomWebView
import com.msp1974.vacompanion.utils.CustomWebViewClient
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Helpers.Companion.isAndroidThings
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.Permissions
import com.msp1974.vacompanion.utils.ScreenUtils
import com.msp1974.vacompanion.utils.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.getValue

//MH
import android.Manifest
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.internal.measurement.zzfk
//Overlay
import com.msp1974.vacompanion.overlay.OverlayController
//BLE
import com.msp1974.vacompanion.ble.BleConnectActivity
//MH-end

class MainActivity : BaseMainActivity() {

    private val log = Logger()

    // FireTV-only fields
    private var overlayController: OverlayController? = null
    private var movedToBack by mutableStateOf(false)
    private var overlayPermitted = false
    private var moveToBackScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lbm by lazy { androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this) }
    private var bleConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register BLE state receiver
        lbm.registerReceiver(
            bleStateRx,
            IntentFilter(com.msp1974.vacompanion.ble.BleRemoteService.ACTION_CONNECTION_STATE)
        )
    }

    // Only override what's different:
    override fun buildContent() {
        setContent {
            val vaUiState by viewModel.vacaState.collectAsState()
            AppTheme(darkMode = config.darkMode, dynamicColor = false) {

                //MH - have we dismissed the app to the background?
                if (movedToBack) {
                    com.msp1974.vacompanion.OverlayShell(viewModel)
                } else {
                    //MH
                    Surface(
                        modifier = Modifier
                            .fillMaxSize(),
                        color = Color.Black
                    ) {
                        if (vaUiState.satelliteRunning) {
                            if (vaUiState.screenBlank) {
                                BlackScreen()
                            } else {
                                WebViewScreen(webView)
                            }
                        } else {
                            if (vaUiState.screenBlank) {
                                BlackScreen()
                            } else {
                                ConnectionScreen()
                            }
                        }
                        when {
                            vaUiState.alertDialog != null -> {
                                VADialog(
                                    onDismissRequest = {
                                        vaUiState.alertDialog!!.onDismiss()
                                    },
                                    onConfirmation = {
                                        vaUiState.alertDialog!!.onConfirm()
                                    },
                                    dialogTitle = vaUiState.alertDialog!!.title,
                                    dialogText = vaUiState.alertDialog!!.message,
                                    confirmText = vaUiState.alertDialog!!.confirmText,
                                    dismissText = vaUiState.alertDialog!!.dismissText
                                )
                            }
                        }
                    }
                } //MH
            }
        }
    }

    override fun onDestroy() {
        try { lbm.unregisterReceiver(bleStateRx) } catch (_: Throwable) { }
        super.onDestroy()
    }

    override fun onSatelliteStarted() {
        //MH
        // Fullscreen; connect to BLE remote
        if (BuildConfig.FLAVOR == "firetv") {
            if (!movedToBack) { // if we've already moved to back, this is likely a reconnect to HA
                log.i("BLE: launching full-screen connect flow")
                bleConnectLauncher.launch(
                    Intent(
                        this@MainActivity,
                        BleConnectActivity::class.java
                    )
                )
            }
            else {
                // After the app has already gone into overlay/background mode,
                // just reconnect silently with no visible UI.
                log.i("BLE: SATELLITE_STARTED while already backgrounded – ignoring BLE connect flow")

                // Mark satellite as running again (we set it to false on SATELLITE_STOPPED)
                viewModel.setSatelliteRunning(true)

                // Re-install overlay host & reload HA URL so /view-assist routes work again
                initOverlay()
                //val url = AuthUtils.getURL(webViewClient.getHAUrl())
                val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
                log.d("Reloading URL after reconnect: $url")
                webView.loadUrl(url)
            }
        }
        else { // non fire-tv
            viewModel.setSatelliteRunning(true)
            initOverlay() //MH
            webView.setZoomLevel(config.zoomLevel)
            config.screenOn = screen.isScreenOn()
            val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
            log.d("Loading URL: $url")
            webView.loadUrl(url)
        }
        //MH-end
    }

    // Force audio permission, skip the request
    override fun getAudioPermissions(
        requiredPermissions: Array<String>,
        requestID: Int
    ): Pair<Array<String>, Int> {
        config.hasRecordAudioPermission = true
        return Pair(requiredPermissions, requestID)
    }

    // Add Bluetooth permissions
    override fun getExtraPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                .toTypedArray()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else
                arrayOf()
        }
    }

    override fun checkAndRequestWriteSettingsPermission() {
        log.w("Write settings permission disabled by flavor; skipping request")
        checkAndRequestNotificationAccessPolicyPermission();
        return
    }

    override fun checkAndRequestNotificationAccessPolicyPermission() {
        log.w("Skipping DND permission prompt (flavor disables DND).")
        initialise();
        return
    }

    //FireTv methods

    fun initOverlay() {
        overlayPermitted = Settings.canDrawOverlays(this)
        if (!overlayPermitted) {
            AlertDialog.Builder(this)
                .setTitle("Fire TV overlay permission required")
                .setMessage(
                    "On Fire TV there is no settings screen to grant overlay permission. " +
                            "Enable via ADB:\n" +
                            "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow\n\n" +
                            "Then restart the app."
                )
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
            log.w("initOverlay: permission missing → running full-screen (no overlay install)")
            webView.setBackgroundColor(0x00000000)
            return
        }

        overlayController = OverlayController.install(this, webView)
        webView.setBackgroundColor(0x00000000)
    }

    fun prepareForOverlay() {
        // Delay moving to back until we actually have an auth token
        if (config.refreshToken.isNullOrBlank()) {
            waitForAuthThenMoveToBack()
            viewModel.setSatelliteRunning(true)
            //val url = AuthUtils.getURL(webViewClient.getHAUrl())
            val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
            log.d("Loading URL: $url")
            webView.loadUrl(url)

        } else {
            moveTaskToBackNow()
        }
    }

    private fun moveTaskToBackNow() {
        initOverlay()
        if (!overlayPermitted || movedToBack) return
        movedToBack = true
        mainHandler.postDelayed({
            moveTaskToBack(true)
            log.i("MainActivity: moved task to back; overlay will render on top")
        }, 300)
        viewModel.setSatelliteRunning(true)

        //val url = AuthUtils.getURL(webViewClient.getHAUrl())
        val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
        log.d("Loading URL: $url")
        webView.loadUrl(url)
    }

    private fun waitForAuthThenMoveToBack(
        pollMs: Long = 250L,
        timeoutMs: Long = 1200_000L
    ) {
        if (moveToBackScheduled || movedToBack) return
        moveToBackScheduled = true

        val start = android.os.SystemClock.uptimeMillis()

        fun tick() {
            val hasToken = !config.refreshToken.isNullOrBlank()
            if (hasToken) {
                moveTaskToBackNow()
                moveToBackScheduled = false
                return
            }
            val elapsed = android.os.SystemClock.uptimeMillis() - start
            if (elapsed >= timeoutMs) {
                log.w("Auth token not available after ${timeoutMs}ms; leaving activity in foreground.")
                moveToBackScheduled = false
                return
            }
            mainHandler.postDelayed({ tick() }, pollMs)
        }
        tick()
    }

    private val bleConnectLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { _ ->
            // Activity emits broadcasts for SKIP/TIMEOUT/SELECTION; service emits CONNECTED.
            viewModel.setSatelliteRunning(true)
        }

    // BLE state listener
    private val bleStateRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, i: android.content.Intent) {
            if (i.action == com.msp1974.vacompanion.ble.BleRemoteService.ACTION_CONNECTION_STATE) {
                val connected = i.getBooleanExtra(com.msp1974.vacompanion.ble.BleRemoteService.EXTRA_CONNECTED, false)
                val reason = i.getStringExtra(com.msp1974.vacompanion.ble.BleRemoteService.EXTRA_REASON)
                bleConnected = connected
                log.i("BLE: state connected=$connected reason=${reason ?: "-"}")

                // Proceed when:
                //  - actually connected, OR
                //  - user explicitly skipped, OR
                //  - countdown timed out, OR
                //  - user explicitly SELECTED a device (listening_selected).
                // Ignore plain "listening" (auto-select of remembered device) to preserve 5s UX.
                if (connected ||
                    reason == com.msp1974.vacompanion.ble.BleRemoteService.REASON_SKIPPED ||
                    reason == com.msp1974.vacompanion.ble.BleRemoteService.REASON_TIMEOUT ||
                    reason == com.msp1974.vacompanion.ble.BleRemoteService.REASON_LISTENING_SELECTED) {
                    prepareForOverlay()
                }
            }
        }
    }
    //MH-end
}

// -----------------------------------------------------------------------------
// Simple shell when overlay is active (so we don't embed the WebView twice)
// -----------------------------------------------------------------------------
@Composable
fun OverlayShell(viewModel: com.msp1974.vacompanion.ui.VAViewModel) {
    val state by viewModel.vacaState.collectAsState()
    Surface(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f)
    ) {
        state.alertDialog?.let { d ->
            com.msp1974.vacompanion.ui.components.VADialog(
                onDismissRequest = { d.onDismiss() },
                onConfirmation = { d.onConfirm() },
                dialogTitle = d.title,
                dialogText = d.message,
                confirmText = d.confirmText,
                dismissText = d.dismissText
            )
        }
    }
}