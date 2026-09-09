package com.msp1974.vacompanion

import android.Manifest
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
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.ScreenOnMode
import com.msp1974.vacompanion.device.ScreenUtils
import com.msp1974.vacompanion.service.VAForegroundService
import com.msp1974.vacompanion.settings.BackgroundTaskStatus
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.ui.VADialog
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.VADialog
import com.msp1974.vacompanion.ui.layouts.BlackScreen
import com.msp1974.vacompanion.ui.layouts.ConnectionScreen
import com.msp1974.vacompanion.ui.layouts.SettingsLayout
import com.msp1974.vacompanion.ui.layouts.WebViewScreen
import com.msp1974.vacompanion.ui.theme.AppTheme
import com.msp1974.vacompanion.utils.CustomWebView
import com.msp1974.vacompanion.utils.CustomWebViewClient
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Permissions
import com.msp1974.vacompanion.utils.SoundControl
import com.msp1974.vacompanion.utils.Updater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), EventListener, ComponentCallbacks2 {

    @Inject lateinit var deviceManager: DeviceManager
    private val deviceInfo get() = deviceManager.deviceInfo

    val viewModel: VAViewModel by viewModels()

    private val config get() = deviceManager.config
    
    private var firebaseManager: FirebaseManager? = null

    private lateinit var webView: CustomWebView
    private lateinit var webViewClient: CustomWebViewClient

    private lateinit var screen: ScreenUtils
    private lateinit var updater: Updater
    private lateinit var permissions: Permissions
    private var screenOrientation: Int = 0
    private var updateProcessComplete: Boolean = true
    private var initialised: Boolean = false
    private var hasNetwork: Boolean = false
    private var screenOffStartUp: Boolean = false
    private var screenOffInProgress: Boolean = false
    private var screenModeJob: Job? = null
    private var screenDelayJob: Job? = null
    private var lastScreenStateEvent: Long = 0
    private var motionDetected: Boolean = false
    private val snackbarHostState = SnackbarHostState()



    @OptIn(ExperimentalMirrorMode::class)
    @SuppressLint("HardwareIds", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {

        val splashscreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        screen = ScreenUtils(this, config)
        updater = Updater(this)
        permissions = Permissions(this, deviceManager, this)

        var keepSplashScreen = true

        firebaseManager = try {
            FirebaseManager.getInstance(this)
        } catch (e: Exception) {
            Timber.w("Firebase unavailable: ${e.message}")
            null
        }
        enableEdgeToEdge()

        splashscreen.setKeepOnScreenCondition { keepSplashScreen }

        onBackPressedDispatcher.addCallback(this, onBackButton)
        setFirebaseUserProperties()

        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        setStatus(getString(R.string.status_initialising))
        keepSplashScreen = false

        // Wake screen on boot if off - keep black.
        if (!screen.isScreenOn()  && screen.isScreenOff()) {
            Timber.i("Performing screen off startup....")
            screenOffStartUp = true
            applyScreenMode(ScreenOnMode.ON_DARK)
        } else {
            screenOffStartUp = false
            Timber.i("Performing screen on startup....")
            applyScreenMode(ScreenOnMode.ON)
        }

        // Init webview setup
        initWebView()

        setContent {
            val vaUiState by viewModel.vacaState.collectAsState()
            AppTheme(darkMode = vaUiState.darkMode, dynamicColor = false) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = Color.Black
                ) { padding ->
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black
                    ) {
                        when {
                            vaUiState.screenBlank -> BlackScreen()
                            vaUiState.satelliteRunning -> {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    WebViewScreen(webView)
                                    if (vaUiState.showMenu) {
                                        SettingsLayout(
                                            viewModel = viewModel,
                                            onClose = {
                                                viewModel.setShowMenu(false)
                                            },
                                            modifier = Modifier.padding(padding)
                                        )
                                    }
                                }
                            }
                            else -> {
                                Box(modifier = Modifier.padding(padding)) {
                                    ConnectionScreen()
                                }
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
                }
            }
        }

        // Check and get required user permissions
        Timber.d("Checking permissions")
        updatePermissionStatus()
        if (!viewModel.vacaState.value.permissions.hasCorePermissions || !viewModel.vacaState.value.permissions.hasOptionalPermissions) {
            // Turn on screen for startup to show permission request
            screenOffStartUp = false

            setScreenSettings()
            permissions.requestCorePermissions { checkAndRequestWriteSettingsPermission() }
        } else {
            Timber.d("All permissions already granted")
            initialise()
        }

    }

    private fun currentScreenMode(): ScreenOnMode {
        return if (screen.isScreenOn()) {
            if (viewModel.vacaState.value.screenBlank) {
                ScreenOnMode.ON_DARK
            } else {
                ScreenOnMode.ON
            }
        } else {
            ScreenOnMode.OFF
        }
    }

    private fun applyScreenMode(mode: ScreenOnMode) {
        Timber.d("MainActivity - Applying screen mode: $mode")
        screenModeJob?.cancel()
        screenModeJob = lifecycleScope.launch {
            if (mode == ScreenOnMode.OFF) screenOffInProgress = true
            try {
                screen.setScreenMode(
                    mode = mode,
                    window = window,
                    isDeviceAdmin = permissions.isDeviceAdmin(),
                    setOverlay = { viewModel.setScreenBlank(it) }
                )
            } finally {
                screenOffInProgress = false
                config.screenSaver = mode == ScreenOnMode.ON_DARK
            }
        }
    }

    fun setScreenSettings() {
        // Hide system bars
        Timber.d("Setting screen settings: Initialised: $initialised, ScreenOffStart: $screenOffStartUp, Sat Running: ${viewModel.vacaState.value.satelliteRunning}")
        screen.hideSystemUI(window)
    }

    fun initWebView() {
        webViewClient = CustomWebViewClient(viewModel)
        webView = CustomWebView.getView(this)
        webView.initialise(deviceManager, webViewClient)
        webView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val onBackButton = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {}
    }

    fun setFirebaseUserProperties() {
        firebaseManager?.setUserProperty("webview_version", deviceInfo.software.webViewVersion)
        firebaseManager?.setUserProperty("device_signature", Helpers.getDeviceName().toString())

        firebaseManager?.setCustomKeys(mapOf(
            "Webview" to deviceInfo.software.webViewVersion,
            "Device" to Helpers.getDeviceName().toString(),
            "UUID" to config.uuid
        ))
    }

    fun initialise() {
        lifecycleScope.launch {
            initialiseApp()
        }
    }

    suspend fun initialiseApp() {
        Timber.d("Initialising.....")

        if (!screenOffStartUp) {
            screen.setScreenAlwaysOn(window, true)
        }

        if (!viewModel.vacaState.value.permissions.hasCorePermissions) {
            setStatus(getString(R.string.status_no_permissions))
            applyScreenMode(ScreenOnMode.ON)
            return
        }
        Timber.d("Permissions OK")
        hasNetwork = Helpers.isNetworkAvailable(this)
        while (!hasNetwork) {
            setStatus(getString(R.string.status_waiting_for_network))
            Timber.w("No Network...")
            delay(2.seconds)
            hasNetwork = Helpers.isNetworkAvailable(this)
        }
        Timber.d("Network active")

        while (!screen.isScreenOn()) {
            Timber.d("Waiting for screen on...")
            delay(1.seconds)
        }
        Timber.d("Screen on")

        if (initialised) return

        // Make volume keys adjust music stream
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Add broadcast receiver
        val filter = IntentFilter().apply {
            addAction(BroadcastSender.SATELLITE_STARTED)
            addAction(BroadcastSender.SATELLITE_CLIENT_UPDATED)
            addAction(BroadcastSender.SATELLITE_STOPPED)
            addAction(BroadcastSender.VERSION_MISMATCH)
            addAction(BroadcastSender.WEBVIEW_CRASH)
            addAction(BroadcastSender.TOAST_MESSAGE)
            addAction(BroadcastSender.CLOSE_APP)
            addAction(BroadcastSender.OPEN_PERMISSION_SCREEN)
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(satelliteBroadcastReceiver, filter)

        val screenIntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        registerReceiver(satelliteBroadcastReceiver, screenIntentFilter)


        config.eventBroadcaster.addListener(this)
        config.currentActivity = "Main"

        // Start background tasks
        runBackgroundTasks()
    }

    // Initiate wake word broadcast receiver
    val satelliteBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Timber.d("Broadcast received: ${intent.action}")
            when (intent.action) {
                BroadcastSender.SATELLITE_STARTED -> {
                    setScreenSettings()
                    webView.setZoomLevel(config.zoomLevel)
                    config.screenOn = screen.isScreenOn()
                    val url = deviceManager.authenticationManager.getHAUrl()
                    Timber.d("Satellite started -> loading URL: $url")
                    webView.loadUrl(url)
                }
                BroadcastSender.SATELLITE_CLIENT_UPDATED -> {
                    val webviewState = viewModel.vacaState.value.webViewPageLoadingStage
                    if ( webviewState == PageLoadingStage.ERROR || webviewState == PageLoadingStage.AUTH_REQUIRED) {
                        webView.refresh()
                    }
                }
                BroadcastSender.SATELLITE_STOPPED -> {
                    if (!config.backgroundTaskRunning) {
                        finishAndRemoveTask()
                    }
                }
                BroadcastSender.VERSION_MISMATCH -> {
                    applyScreenMode(ScreenOnMode.ON)
                    runUpdateRoutine()
                }
                BroadcastSender.RUN_UPDATE -> {
                    applyScreenMode(ScreenOnMode.ON)
                    downloadAndInstallUpdate()
                }
                BroadcastSender.OPEN_PERMISSION_SCREEN -> {
                    val specificPermission = intent.getStringExtra("extra")
                    if (specificPermission != null) {
                        openPermissionScreen(specificPermission)
                    } else {
                        permissions.requestCorePermissions { checkAndRequestWriteSettingsPermission() }
                    }
                }
                BroadcastSender.WEBVIEW_CRASH -> {
                    initWebView()
                    val url = deviceManager.authenticationManager.getHAUrl()
                    Timber.d("Webview crash -> loading URL: $url")
                    webView.loadUrl(url)
                }
                BroadcastSender.CLOSE_APP -> {
                    terminateApp()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Handles if screen woken by hardware button
                    if (initialised && !config.screenOn) {
                        config.screenOn = true
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    //Handles if screen off by hardware button or timeout
                    if (config.screenOn) {
                        config.screenOn = false
                    }
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val dndEnabled = SoundControl.isDoNotDisturbEnabled(context)
                    if (deviceManager.status.value.isDND != dndEnabled) {
                        deviceManager.updateDNDStatus(dndEnabled)
                    }
                }
                BroadcastSender.TOAST_MESSAGE -> {
                    val msg = intent.getStringExtra("extra") ?: ""
                    if (msg.isNotEmpty()) {
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }



    fun setStatus(status: String) {
        viewModel.setStatusMessage(status)
    }

    fun runUpdateRoutine() {
        try {
            updateProcessComplete = false
            setStatus(getString(R.string.status_checking_for_update))
            lifecycleScope.launch {
                checkForUpdate()
            }
        } catch (_: Exception) {
            setStatus(getString(R.string.status_app_update_required, config.minRequiredApkVersion))
        }
    }

    // Listening to the orientation config
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != screenOrientation) {
            Timber.d("Orientation changed to ${newConfig.orientation}")
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("Main Activity resumed")

        // Only resume the webview if it should currently be visible - not while intentionally
        // blanked (screensaver/screenSaver flag), otherwise this would wake up any autoplaying
        // dashboard video (e.g. a live camera card) and drive media.codec CPU while blanked.
        if (::webView.isInitialized && !viewModel.vacaState.value.screenBlank) {
            webView.onResume()
            webView.resumeTimers()
        }

        // Catch if background tasks not running
        if (initialised && Helpers.isNetworkAvailable(this) && config.backgroundTaskStatus == BackgroundTaskStatus.NOT_STARTED ) {
            Timber.e("Background task starting on resume as is is not running")
            lifecycleScope.launch {
                runBackgroundTasks()
            }
        }
        setScreenSettings()
    }

    override fun onPause() {
        Timber.d("Main Activity paused")
        // Stop any WebView processing (including in-page video/WebRTC decode) while not visible,
        // rather than relying on the view merely being detached from the window.
        if (::webView.isInitialized) {
            webView.onPause()
            webView.pauseTimers()
        }
        super.onPause()
    }

    override fun onDestroy() {
        Timber.d("Main Activity destroyed")
        try {
            screen.setScreenTimeout(config.screenTimeout)
            config.eventBroadcaster.removeListener(this)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(satelliteBroadcastReceiver)
            unregisterReceiver(satelliteBroadcastReceiver)
        } catch (e: Exception) {
            Timber.e("Error destroying MainActivity: ${e.message}")
        } finally {
            super.onDestroy()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (config.screenSaver && config.screenSaverDisableOnTouch) {
            config.screenSaver = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun terminateApp() {
        finishAffinity()
    }

    private suspend fun runBackgroundTasks() {
        if ( config.backgroundTaskStatus != BackgroundTaskStatus.NOT_STARTED ) {
            Timber.w("Background task already running.  Not starting from MainActivity")
            firebaseManager?.logEvent(FirebaseManager.MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING, mapOf())
            if (viewModel.vacaState.value.satelliteRunning) {
                webView.setZoomLevel(config.zoomLevel)
                val url = deviceManager.authenticationManager.getHAUrl()
                Timber.d("Run background tasks -> loading URL: $url")
                webView.loadUrl(url)
            } else {
                setStatus(getString(R.string.status_waiting_for_connection))
            }
            return
        }
        config.backgroundTaskStatus = BackgroundTaskStatus.STARTING

        if (!updateProcessComplete) {
            delay(1.seconds)
            runUpdateRoutine()
            return
        }
        Timber.d("Starting background tasks")
        setStatus(getString(R.string.status_waiting_for_connection))
        try {
            Intent(this.applicationContext, VAForegroundService::class.java).also {
                it.action = VAForegroundService.Actions.START.toString()
                startService(it)
            }
        } catch (ex: Exception) {
            Timber.w("Error starting background tasks - ${ex.message}")
            config.backgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED
        }

        if (screenOffStartUp) {
            Timber.d("Screen off startup.  Reverting to screen off")
            delay(2.seconds)
            applyScreenMode(ScreenOnMode.OFF)
            screenOffStartUp = false
        }
        initialised = true
        Timber.d("Initialised")
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        runOnUiThread {
            if (!screenOffInProgress) {
                when (event.eventName) {
                    "screenAlwaysOn" -> {
                        val enabled = event.newValue as Boolean
                        screen.setScreenAlwaysOn(window, enabled)
                    }
                    "screenAutoBrightness" -> {
                        val enabled = event.newValue as Boolean
                        if (screen.isScreenOn() and !viewModel.vacaState.value.screenBlank) {
                            screen.setScreenAutoBrightness(enabled)
                            if (!enabled) screen.setScreenBrightness(window, config.screenBrightness)
                        }
                    }
                    "screenBrightness" -> {
                        if (screen.isScreenOn() and !viewModel.vacaState.value.screenBlank) {
                            screen.setScreenBrightness(window, event.newValue as Float)
                        }
                    }
                    "screenTimeout" -> screen.setScreenTimeout(config.screenTimeout)
                    "hideSystemUI" -> screen.hideSystemUI(window)
                    else -> consumed = false
                }
                if (consumed) {
                    Timber.d("MainActivity - Setting: ${event.eventName} - ${event.newValue}")
                }
            }


            consumed = true

            when (event.eventName) {
                "zoomLevel" -> webView.setZoomLevel(event.newValue as Int)
                "textSize" -> webView.setTextSize(event.newValue as Int)
                "darkMode" -> setDarkMode(event.newValue as Boolean)
                "refresh" -> webView.refresh()
                "screenOn" -> handleScreenOnChange(event.newValue as Boolean)
                "screenSaver" -> onScreenSaver(event.newValue as Boolean)
                "screenOrientationMode" -> screen.setScreenOrientation(this@MainActivity, event.newValue as String)
                "deviceBump" -> if (config.screenOnBump) applyScreenMode(ScreenOnMode.ON)
                "proximity" -> if (config.screenOnProximity && event.newValue as Float == 0f) applyScreenMode(ScreenOnMode.ON)
                "motion" -> onMotion(event.newValue as Boolean)
                "showToastMessage" -> Toast.makeText(
                    this,
                    event.newValue as String,
                    Toast.LENGTH_SHORT
                ).show()
                "showToastError" -> showSnackbar("${event.newValue}")
                else -> consumed = false
            }
            if (consumed) {
                Timber.d("MainActivity - Event: ${event.eventName} - ${event.newValue}")
            }
        }
    }

    fun handleScreenOnChange(screenOn: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastScreenStateEvent > 5000) {
            if (screen.isScreenOn() != screenOn) {
                applyScreenMode(if (screenOn) ScreenOnMode.ON else ScreenOnMode.OFF)
                lastScreenStateEvent = now
            }
        } else {
            if (screenDelayJob != null && screenDelayJob!!.isActive) screenDelayJob!!.cancel()
            screenDelayJob = lifecycleScope.launch {
                delay(2.seconds)
                applyScreenMode(if (config.screenOn) ScreenOnMode.ON else ScreenOnMode.OFF)
                lastScreenStateEvent = now
            }
        }
    }

    fun onScreenSaver(enabled: Boolean) {
        applyScreenMode(
            if (enabled) {
                ScreenOnMode.ON_DARK
            } else {
                if (screen.isScreenOn()) ScreenOnMode.ON else ScreenOnMode.OFF
            }
        )
    }

    fun onMotion(isDetected: Boolean) {
        if (isDetected && !motionDetected) {
            if (config.screenOnMotion && currentScreenMode() != ScreenOnMode.ON) {
                applyScreenMode(ScreenOnMode.ON)
            }
            if (!config.screenAlwaysOn) screen.setScreenAlwaysOn(window, true)
        } else {
            if (!config.screenAlwaysOn) screen.setScreenAlwaysOn(window, false)
        }
        motionDetected = isDetected
    }

    fun setDarkMode(isDark: Boolean) {
        Timber.d("Setting dark mode: $isDark")
        try {
            if (isDark) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }

            // Set device dark mode
            val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                uiModeManager.setApplicationNightMode(if (isDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
            } else {
                uiModeManager.nightMode =
                    if (isDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            }
        } catch (e: Exception) {
            Timber.w("Error setting dark mode: ${e.message}")
        }

        webView.refreshDarkMode(isDark)
    }

    private fun showSnackbar(message: String) {
        lifecycleScope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
        }
    }

    private fun updatePermissionStatus() {
        val corePermissions = permissions.hasCorePermissions()
        val optionalPermissions = permissions.hasOptionalPermissions()
        Timber.d("Core permissions: $corePermissions")
        Timber.d("Optional permissions: $optionalPermissions")
        viewModel.setPermissionsStatus(corePermissions, optionalPermissions)
    }

    // Runtime permissions go through Permissions.requestPermission(), which uses its own
    // dedicated ActivityResultLauncher/callback per request - so requesting one permission here
    // (e.g. BLUETOOTH_CONNECT for a Bluetooth mic, requested well after startup) can't trigger
    // logic meant for a different request, unlike the old shared onRequestPermissionsResult()
    // override this replaced.
    private fun openPermissionScreen(permission: String) {
        Timber.d("Opening permission screen: $permission")
        when (permission) {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.BLUETOOTH_CONNECT -> {
                permissions.requestPermission(permission) { granted ->
                    updatePermissionStatus()
                    if (granted) {
                        BroadcastSender.sendBroadcast(this, BroadcastSender.PERMISSION_GRANTED, permission)
                    }
                }
            }
            "WRITE_SETTINGS" -> {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:$packageName".toUri())
                onWriteSettingsPermissionActivityResult.launch(intent)
            }
            "NOTIFICATION_POLICY" -> {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                onNotificationAccessPolicyPermissionActivityResult.launch(intent)
            }
            "DEVICE_ADMIN" -> {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, VACADeviceAdminReceiver::class.java))
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "This application requires Device Admin rights to be able to control the screen.")
                onDeviceAdminPermissionActivityResult.launch(intent)
            }
        }
    }

    private val onWriteSettingsPermissionActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updatePermissionStatus()
        checkAndRequestNotificationAccessPolicyPermission()
    }

    private fun checkAndRequestWriteSettingsPermission() {
        if (config.canSetScreenWritePermission && !Settings.System.canWrite(applicationContext)) {
            val alertDialog = AlertDialog.Builder(this)
            Timber.d("Requesting write settings permission")
            alertDialog.apply {
                setTitle("Write Settings Permission Required")
                setMessage("This application needs this permission to control the Auto brightness setting.  If your device requires explicit permission, the screen will launch for you to enable it.")
                setPositiveButton("Got it") { _: DialogInterface?, _: Int ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        onWriteSettingsPermissionActivityResult.launch(intent)
                    } catch (_: Exception) {
                        Timber.i("Device does not require explicit permission")
                        config.canSetScreenWritePermission = false
                        checkAndRequestNotificationAccessPolicyPermission()
                    }
                }
            }.create().show()
        } else {
            Timber.d("Write settings permission ${if (!config.canSetScreenWritePermission) "not required" else "granted"}")
            checkAndRequestNotificationAccessPolicyPermission()
        }
    }

    private val onNotificationAccessPolicyPermissionActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Timber.i("Notification access policy permission result -> ${it.resultCode}")
        if (it.resultCode == RESULT_CANCELED) {
            config.canSetNotificationPolicyAccess = false
        }
        updatePermissionStatus()
        checkAndRequestDeviceAdminPermission()
    }

    private fun checkAndRequestNotificationAccessPolicyPermission() {
        val notificationManager =  this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (config.canSetNotificationPolicyAccess && !notificationManager.isNotificationPolicyAccessGranted) {
            // If not granted, prompt the user to give permission.
            val alertDialog = AlertDialog.Builder(this)
            Timber.d("Requesting notification access policy permission")
            alertDialog.apply {
                setTitle("Notification Policy Access Permission Required")
                setMessage("This application needs this permission to control the Do Not Disturb setting.  If your device has this capability and requires explicit permission, the screen will launch for you to enable it.")
                setPositiveButton("Got it") { _: DialogInterface?, _: Int ->
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        onNotificationAccessPolicyPermissionActivityResult.launch(intent)
                    } catch (_: Exception) {
                        Timber.i("Device does not require explicit permission")
                        checkAndRequestDeviceAdminPermission()
                    }
                }
            }.create().show()
        } else {
            Timber.d("Notification access policy permission already granted or not supported")
            config.hasPostNotificationPermission = true
            checkAndRequestDeviceAdminPermission()
        }
    }

    private val onDeviceAdminPermissionActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Timber.i("Device Admin permission result -> ${it.resultCode}")
        updatePermissionStatus()
        initialise()
    }

    private fun checkAndRequestDeviceAdminPermission() {
        if (!deviceInfo.software.isAndroidThings && !permissions.isDeviceAdmin()) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, VACADeviceAdminReceiver::class.java))
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "This application requires Device Admin rights to be able to control the screen.")
            onDeviceAdminPermissionActivityResult.launch(intent)
        } else {
            Timber.d("Device admin permission already granted or not supported")
            initialise()
        }
    }

    private suspend fun checkForUpdate() {
        try {
            Timber.d("Checking for update")
            if (updater.isUpdateAvailable(config.minRequiredApkVersion)) {
                Timber.d("Update available - ${updater.latestRelease.downloadURL}")

                val a = VADialog(
                    title = "Update Required",
                    message = "You require a minimum of v${config.minRequiredApkVersion} of this app to connect to your server.  Do you wish to download and install this version now?",
                    confirmCallback = {
                        downloadAndInstallUpdate()
                    },
                    dismissCallback = {
                        updateProcessComplete = true
                        viewModel.vacaState.value.updates.updateAvailable = true
                        setStatus(
                            getString(
                                R.string.status_app_update_required,
                                config.minRequiredApkVersion
                            )
                        )
                    }
                )
                viewModel.showUpdateDialog(a)
            } else {
                updateProcessComplete = true
                setStatus("Incompatible version. In app update not available")
            }
        } catch (ex: Exception) {
            Timber.e("Error checking for update - ${ex.message}")
            updateProcessComplete = true
            setStatus("Incompatible version. In app update not available")
        }
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    @Suppress("DEPRECATION")
    private fun downloadAndInstallUpdate() {
        setStatus(getString(R.string.status_downloading_update))
        updater.requestDownload { uri ->
            if (uri != "") {
                Timber.d("Download complete = $uri")
                setStatus(getString(R.string.status_installing_update))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                    intent.setData(uri.toUri())
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    onUpdateAppActivityResult.launch(intent)
                } else {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.setDataAndType(
                        uri.toUri(),
                        "application/vnd.android.package-archive"
                    )
                    onUpdateAppActivityResult.launch(intent)
                }
            } else {
                val b = VADialog(
                    title = "Error downloading update",
                    message = "There was an error downloading the update.  This could be caused by a lack of disk space or an error accessing the internet.",
                    confirmCallback = {
                        updateProcessComplete = true
                        setStatus(getString(R.string.status_app_update_required, config.minRequiredApkVersion))
                    },
                    dismissCallback = {}
                )
                viewModel.showUpdateDialog(b)
            }
        }
    }

    private val onUpdateAppActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateProcessComplete = true
        setStatus("Incompatible version. In app update not available")
    }

    override fun onTrimMemory(level: Int) {
        // Try and prevent the app being killed by memory manager
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // Release memory related to UI elements, such as bitmap caches.
            firebaseManager?.logEvent(FirebaseManager.TRIM_MEMORY_UI_HIDDEN, mapOf())
            Runtime.getRuntime().gc()

        }

        if (level >= TRIM_MEMORY_BACKGROUND) {
            // Release memory related to background processing, such as by
            // closing a database connection.
            firebaseManager?.logEvent(FirebaseManager.TRIM_MEMORY_BACKGROUND, mapOf())
            Runtime.getRuntime().gc()
        }

        super.onTrimMemory(level)
    }
}
