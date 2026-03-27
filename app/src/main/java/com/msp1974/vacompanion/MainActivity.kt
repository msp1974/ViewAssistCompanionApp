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
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
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
import java.net.URL
import kotlin.getValue


class MainActivity : AppCompatActivity(), EventListener, ComponentCallbacks2 {
    val viewModel: VAViewModel by viewModels()

    private val log = Logger()
    private val firebase = FirebaseManager.getInstance()

    private lateinit var config: APPConfig
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
    private var screenSleepWaitJob: Job? = null
    private var idleSignalJob: Job? = null



    @OptIn(ExperimentalMirrorMode::class)
    @SuppressLint("HardwareIds", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {

        config = APPConfig.getInstance(this)
        screen = ScreenUtils(this)
        updater = Updater(this)
        permissions = Permissions(this)

        viewModel.bind(APPConfig.getInstance(this),resources)

        val splashscreen = installSplashScreen()
        var keepSplashScreen = true

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        splashscreen.setKeepOnScreenCondition { keepSplashScreen }

        onBackPressedDispatcher.addCallback(this, onBackButton)
        setFirebaseUserProperties()

        log.i("#################################################################################################")
        log.i("Starting View Assist Companion App")
        log.i("Version ${config.version}")
        log.i("Android version: ${Helpers.getAndroidVersion()}")
        log.i("Name: ${Helpers.getDeviceName()}")
        log.i("Serial: ${Build.SERIAL}")
        log.i("UUID: ${config.uuid}")
        log.i("#################################################################################################")

        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        Thread.setDefaultUncaughtExceptionHandler(AppExceptionHandler(this))

        setStatus(getString(R.string.status_initialising))
        keepSplashScreen = false

        // Wake screen on boot if off - keep black.
        if (!screen.isScreenOn()  && screen.isScreenOff()) {
            Timber.i("Performing screen off startup....")
            screenOffStartUp = true
        } else {
            screenOffStartUp = false
            Timber.i("Performing screen on startup....")
        }

        setScreenSettings()

        // Init webview setup
        initWebView()

        setContent {
            val vaUiState by viewModel.vacaState.collectAsState()
            AppTheme(darkMode = config.darkMode, dynamicColor = false) {
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
            }
        }

        // Check and get required user permissions
        log.d("Checking permissions")
        updatePermissionStatus()
        if (!viewModel.vacaState.value.permissions.hasCorePermissions || !viewModel.vacaState.value.permissions.hasOptionalPermissions) {
            // Need to get permissions
            LocalBroadcastManager.getInstance(this).registerReceiver(satelliteBroadcastReceiver, IntentFilter().apply {
                addAction(BroadcastSender.REQUEST_MISSING_PERMISSIONS)
            })

            // Turn on screen for startup to show permission request
            screenOffStartUp = false

            setScreenSettings()
            checkAndRequestPermissions()
        } else {
            log.d("All permissions already granted")
            initialise()
        }

    }

    fun setScreenSettings() {
        // Hide system bars
        Timber.d("Setting screen settings")
        screen.hideSystemUI(window)

        if (!initialised) {
            // Set screen for loading
            screen.setScreenAlwaysOn(window, true)

            if (screenOffStartUp) {
                config.screenBrightness = screen.getScreenBrightness()
                setScreenSaver(true)
                screenWake()
            } else {
                if (config.screenBrightness <= 0.3) config.screenBrightness = 0.6f
                screen.setScreenBrightness(window, config.screenBrightness)

                config.screenTimeout = screen.getScreenTimeout()
                if (config.screenTimeout < 15000) config.screenTimeout = 15000
                screen.setScreenTimeout(config.screenTimeout)
                setScreenSaver(false)
            }
        } else if (viewModel.vacaState.value.satelliteRunning) {
            screen.setScreenBrightness(window, config.screenBrightness)
            screen.setScreenAutoBrightness(window, config.screenAutoBrightness)
            screen.setScreenTimeout(config.screenTimeout)
            screen.setScreenAlwaysOn(window, config.haNavigateScreensaver || config.screenAlwaysOn)
        }
    }

    fun initWebView() {
        webViewClient = CustomWebViewClient(viewModel)
        webView = CustomWebView.getView(this)
        webView.initialise(config, webViewClient)
        webView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val onBackButton = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {}
    }

    fun setFirebaseUserProperties() {
        val webViewVersion = DeviceCapabilitiesManager(this).getWebViewVersion()
        firebase.setUserProperty("webview_version", webViewVersion)
        firebase.setUserProperty("device_signature", Helpers.getDeviceName().toString())

        firebase.setCustomKeys(mapOf(
            "Webview" to webViewVersion,
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
        if (!viewModel.vacaState.value.permissions.hasCorePermissions) {
            setStatus(getString(R.string.status_no_permissions))
            Timber.w("No Permissions")
            viewModel.setScreenBlank(false)
            screenWake()
            return
        }
        Timber.d("Permissions OK")
        hasNetwork = Helpers.isNetworkAvailable(this)
        while (!hasNetwork) {
            setStatus(getString(R.string.status_waiting_for_network))
            Timber.w("No Network...")
            delay(1000)
            hasNetwork = Helpers.isNetworkAvailable(this)
        }
        Timber.d("Network active")

        while (!screen.isScreenOn()) {
            Timber.d("Waiting for screen on...")
            delay(1000)
        }
        Timber.d("Screen on")

        if (initialised) return

        // Make volume keys adjust music stream
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Add broadcast receiver
        val filter = IntentFilter().apply {
            addAction(BroadcastSender.SATELLITE_STARTED)
            addAction(BroadcastSender.SATELLITE_STOPPED)
            addAction(BroadcastSender.VERSION_MISMATCH)
            addAction(BroadcastSender.WEBVIEW_CRASH)
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

        registerWifiMonitor()

        // Start background tasks
        runBackgroundTasks()
    }

    // Initiate wake word broadcast receiver
    val satelliteBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Timber.d("Broadcast received: ${intent.action}")
            when (intent.action) {
                BroadcastSender.SATELLITE_STARTED -> {
                    viewModel.setSatelliteRunning(true)
                    webView.setZoomLevel(config.zoomLevel)
                    config.screenOn = !screen.isScreenOff()
                    loadStartupUrl()
                    if (shouldRunIdleWatchdog()) {
                        scheduleIdleSignal()
                    }
                }
                BroadcastSender.SATELLITE_STOPPED -> {
                    viewModel.setSatelliteRunning(false)
                    if (!config.backgroundTaskRunning) {
                        finishAndRemoveTask()
                    }
                }
                BroadcastSender.VERSION_MISMATCH -> {
                    runUpdateRoutine()
                }
                BroadcastSender.REQUEST_MISSING_PERMISSIONS -> {
                    checkAndRequestPermissions()
                }
                BroadcastSender.WEBVIEW_CRASH -> {
                    initWebView()
                    loadStartupUrl()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (initialised) {
                        // If woken by hardware buttons set screen config
                        setScreenSettings()
                    }
                    config.screenOn = true
                    if (shouldRunIdleWatchdog()) {
                        scheduleIdleSignal()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    config.screenOn = false
                    if (shouldRunIdleWatchdog()) {
                        cancelIdleSignal("screen-off")
                    }
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val dndEnabled = DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)
                    if (config.doNotDisturb != dndEnabled) {
                        config.doNotDisturb = dndEnabled
                    }
                }
            }
        }
    }


    fun registerWifiMonitor() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        Timber.d("Registering Wifi monitor")
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log.i("Network connection available")
                hasNetwork = true
                viewModel.onNetworkStateChange()
                setStatus(getString(R.string.status_waiting_for_connection))
            }

            override fun onLost(network: Network) {
                log.e("Lost network connection")
                hasNetwork = false
                lifecycleScope.launch {
                    delay(10000)
                    if (!hasNetwork) {
                        viewModel.onNetworkStateChange()
                        setStatus(getString(R.string.status_waiting_for_network))
                        if (config.enableNetworkRecovery) {
                            val delaySecs = 10
                            log.d("Disabling wifi for ${delaySecs}s")
                            Helpers.enableWifi(this@MainActivity, false)
                            delay(delaySecs.toLong() * 1000)
                            log.d("Enabling wifi")
                            Helpers.enableWifi(this@MainActivity, true)
                        }
                    }
                }
            }
        })
    }

    fun setStatus(status: String) {
        viewModel.setStatusMessage(status)
    }

    fun runUpdateRoutine() {
        if (permissions.hasPermission(permission.WRITE_EXTERNAL_STORAGE) && updateProcessComplete) {
            updateProcessComplete = false
            setStatus(getString(R.string.status_checking_for_update))
            lifecycleScope.launch {
                checkForUpdate()
            }
        } else {
            setStatus(getString(R.string.status_app_update_required, config.minRequiredApkVersion))
        }
    }

    // Listening to the orientation config
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != screenOrientation) {
            log.d("Orientation changed to ${newConfig.orientation}")
        }
    }

    override fun onResume() {
        super.onResume()
        log.d("Main Activity resumed")
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(VAForegroundService.RECOVERY_NOTIFICATION_ID)
        config.screenOn = !screen.isScreenOff()
        if (config.haNavigateScreensaver && config.uiIdle) {
            // Resume can occur without user touch (e.g., focus/permission transitions).
            // Do not treat it as user activity while idle-driven screensaver is active.
            scheduleIdleSignal()
        }

        // Catch if background tasks not running
        if (initialised && Helpers.isNetworkAvailable(this) && config.backgroundTaskStatus == BackgroundTaskStatus.NOT_STARTED ) {
            log.e("Background task starting on resume as is is not running")
            lifecycleScope.launch {
                runBackgroundTasks()
            }
        }
        setScreenSettings()
    }

    override fun onDestroy() {
        log.d("Main Activity destroyed")
        idleSignalJob?.cancel()
        idleSignalJob = null
        screen.setScreenTimeout(config.screenTimeout)
        config.eventBroadcaster.removeListener(this)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(satelliteBroadcastReceiver)
        unregisterReceiver(satelliteBroadcastReceiver)
        super.onDestroy()
    }

    private suspend fun runBackgroundTasks() {
        if ( config.backgroundTaskStatus != BackgroundTaskStatus.NOT_STARTED ) {
            log.w("Background task already running.  Not starting from MainActivity")
            firebase.logEvent(FirebaseManager.MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING, mapOf())
            if (config.isRunning) {
                viewModel.setSatelliteRunning(true)
                webView.setZoomLevel(config.zoomLevel)
                loadStartupUrl()
            } else {
                setStatus(getString(R.string.status_waiting_for_connection))
            }
            if (!initialised) {
                initialised = true
                setScreenSettings()
                if (config.haNavigateScreensaver) {
                    scheduleIdleSignal()
                }
                Timber.d("Initialised from existing background task")
            }
            return
        }
        config.backgroundTaskStatus = BackgroundTaskStatus.STARTING

        if (!updateProcessComplete) {
            delay(1000)
            runUpdateRoutine()
            return
        }
        log.d("Starting background tasks")
        setStatus(getString(R.string.status_waiting_for_connection))
        try {
            Intent(this.applicationContext, VAForegroundService::class.java).also {
                it.action = VAForegroundService.Actions.START.toString()
                startService(it)
            }
        } catch (ex: Exception) {
            log.w("Error starting background tasks - ${ex.message}")
            config.backgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED
        }

        if (screenOffStartUp) {
            delay(2000)
            screenSleep()
            screenOffStartUp = false
        }
        setScreenSettings()
        initialised = true
        onUserActivity("initialised")
        Timber.d("Initialised")
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        runOnUiThread {
            if (!screenOffInProgress) {
                when (event.eventName) {
                    "screenAlwaysOn" -> {
                        val enabled = event.newValue as Boolean
                        if (config.haNavigateScreensaver) {
                            if (!enabled) {
                                log.d("Ignoring screenAlwaysOn=false while haNavigateScreensaver=true")
                            }
                            screen.setScreenAlwaysOn(window, true)
                        } else if (config.haNavigateScreensaver && config.uiIdle && !enabled) {
                            log.d("Ignoring screenAlwaysOn=false while uiIdle=true")
                        } else {
                            screen.setScreenAlwaysOn(window, enabled)
                        }
                    }
                    "screenAutoBrightness" -> {
                        if (screen.isScreenOn() and !viewModel.vacaState.value.screenBlank) {
                            screen.setScreenAutoBrightness(
                                window,
                                event.newValue as Boolean
                            )
                        }
                    }
                    "screenBrightness" -> {
                        if (screen.isScreenOn() and !viewModel.vacaState.value.screenBlank) {
                            screen.setScreenBrightness(window, event.newValue as Float)
                        }
                    }
                    "screenTimeout" -> {
                        screen.setScreenTimeout(config.screenTimeout)
                        if (shouldRunIdleWatchdog()) {
                            if (config.uiIdle && !isScreensaverPath(config.currentPath)) {
                                log.d("Clearing stale idle state after screenTimeout update path=${config.currentPath}")
                                setUiIdle(false, "screen-timeout-update")
                            }
                            scheduleIdleSignal(forceRestart = true, reason = "screen-timeout-update")
                        }
                    }
                    "screenSaver" -> {
                        handleScreensaverSettingChanged(event.newValue as Boolean)
                    }
                    "haNavigateScreensaver" -> {
                        if (event.newValue as Boolean) {
                            if (config.uiIdle && !isScreensaverPath(config.currentPath)) {
                                log.d("Clearing stale idle state after haNavigateScreensaver enabled path=${config.currentPath}")
                                setUiIdle(false, "ha-navigate-enabled")
                            }
                            scheduleIdleSignal(forceRestart = true, reason = "ha-navigate-enabled")
                        } else {
                            cancelIdleSignal("ha-navigate-disabled")
                            setUiIdle(false, "ha-navigate-disabled")
                            if (config.screenSaver) {
                                scheduleIdleSignal(forceRestart = true, reason = "ha-navigate-disabled-local")
                            }
                        }
                    }
                    else -> consumed = false
                }
            }
            if (consumed) {
                log.d("MainActivity - Setting: ${event.eventName} - ${event.newValue}")
            }

            consumed = true

            when (event.eventName) {
                "zoomLevel" -> webView.setZoomLevel(event.newValue as Int)
                "darkMode" -> setDarkMode(event.newValue as Boolean)
                "refresh" -> webView.reload()
                "screenWake" -> screenWake()
                "screenSleep" -> screenSleep()
                "screenOrientationMode" -> setScreenOrientation(event.newValue as String)
                "navigate" -> {
                    val path = event.newValue as String
                    navigateToPath(path)
                    handleNonScreensaverNavigation(path, "navigate")
                }
                "currentPath" -> {
                    val path = event.newValue as String
                    handleNonScreensaverNavigation(path, "current-path")
                }
                "deviceBump" -> if (config.screenOnBump) {
                    onUserActivity("device-bump")
                    screenWake()
                }
                "proximity" -> if (config.screenOnProximity && event.newValue as Float == 0f) {
                    onUserActivity("proximity")
                    screenWake()
                }
                "motion" -> onMotion()
                "showToastMessage" -> Toast.makeText(
                    this,
                    event.newValue as String,
                    Toast.LENGTH_SHORT
                ).show()
                else -> consumed = false
            }
            if (consumed) {
                log.d("MainActivity - Event: ${event.eventName} - ${event.newValue}")
            }
        }
    }

    fun onMotion() {
        config.lastMotion = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        if (config.screenOnMotion) {
            onUserActivity("motion")
            screenWake()
        }
    }

    fun setScreenOrientation(mode: String) {
        when (mode) {
            "auto" ->  setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            "portrait" -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            "landscape" -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            "reverse_portrait" -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT)
            "reverse_landscape" -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
        }
    }

    fun screenSaver(active: Boolean) {
        if (active) {
            Timber.d("Enabling screen saver")
            viewModel.setScreenBlank(true)
            screen.setScreenAlwaysOn(window, config.haNavigateScreensaver)
            screen.setScreenAutoBrightness(window, false)
            screen.setScreenBrightness(window, 0.01f)
        } else {
            Timber.d("Disabling screen saver")
            viewModel.setScreenBlank(false)
            screen.setScreenAlwaysOn(window, config.haNavigateScreensaver || config.screenAlwaysOn)
            screen.setScreenAutoBrightness(window, config.screenAutoBrightness)
            screen.setScreenBrightness(window, config.screenBrightness)
        }
    }

    fun setScreenSaver(active: Boolean) {
        if (config.haNavigateScreensaver) {
            if (active) {
                onUserActivity("screensaver-enabled")
                scheduleIdleSignal()
            } else {
                cancelIdleSignal("screensaver-disabled")
                setUiIdle(false, "screensaver-disabled")
            }
            return
        }
        screenSaver(active)
    }

    fun screenWake() {
        Timber.d("Wake screen")
        if (!(config.haNavigateScreensaver && config.uiIdle)) {
            onUserActivity("screen-wake")
        }
        // Cancel any screen sleep timer
        if (screenSleepWaitJob != null && screenSleepWaitJob!!.isActive) {
            screenSleepWaitJob!!.cancel()
        }

        // Experimental fix for screen not turning on on A15+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            this.setTurnScreenOn(true);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        screen.wakeScreen()
        clearTurnScreenOnFlag()

        if (viewModel.vacaState.value.screenBlank && initialised) {
            setScreenSaver(false)
        }
    }

    fun screenSleep() {
        Timber.d("Sleeping screen")
        if (config.haNavigateScreensaver) {
            setUiIdle(true, "screen-sleep")
            return
        }
        clearTurnScreenOnFlag()
        if (permissions.isDeviceAdmin()) {
            screen.setPartialWakeLock()
            lockScreen()
            setScreenSaver(false)
            return
        }

        if (!screenOffInProgress) {
            Timber.d("Sleeping screen via timeout")
            screenOffInProgress = true
            setScreenSaver(true)
            screen.setPartialWakeLock()
            if (screen.setScreenTimeout(1000)) {
                screenSleepWaitJob = lifecycleScope.launch {
                    waitForScreenOff()
                }
            } else {
                config.screenOn = false
                screenOffInProgress = false
            }
        }
    }

    fun lockScreen() {
        if (permissions.isDeviceAdmin()) {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
        }
    }

    suspend fun waitForScreenOff() {
        try {
            delay(1000)
            withTimeout(15000) {
                while (!screen.isScreenOff()) {
                    delay(500)
                }
            }
        } catch (ex: Exception) {
            log.w("Timed out waiting for screen off")
            screenOffInProgress = false
            return
        }
        config.screenOn = false
        screenOffInProgress = false
        log.d("Screen off")
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        onUserActivity("interaction")
    }

    private fun onUserActivity(reason: String) {
        config.lastActivity = System.currentTimeMillis()
        if (config.uiIdle) {
            setUiIdle(false, "activity-$reason")
        }
        if (shouldRunIdleWatchdog()) {
            scheduleIdleSignal(forceRestart = true, reason = "activity-$reason")
        }
    }

    private fun clearTurnScreenOnFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(false)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    private fun shouldRunIdleWatchdog(): Boolean {
        return initialised && (config.haNavigateScreensaver || config.screenSaver)
    }

    private fun handleScreensaverSettingChanged(enabled: Boolean) {
        if (config.haNavigateScreensaver) {
            if (enabled) {
                scheduleIdleSignal(forceRestart = true, reason = "screensaver-setting-enabled")
            } else {
                cancelIdleSignal("screensaver-setting-disabled")
                setUiIdle(false, "screensaver-setting-disabled")
            }
            return
        }

        if (enabled) {
            if (viewModel.vacaState.value.screenBlank) {
                setScreenSaver(false)
            }
            scheduleIdleSignal(forceRestart = true, reason = "screensaver-setting-enabled")
        } else {
            cancelIdleSignal("screensaver-setting-disabled")
            if (viewModel.vacaState.value.screenBlank || screenOffInProgress || screen.isScreenOff()) {
                setScreenSaver(false)
            }
        }
    }

    private fun scheduleIdleSignal(forceRestart: Boolean = false, reason: String = "state-change") {
        config.screenOn = !screen.isScreenOff()
        if (!shouldRunIdleWatchdog()) {
            log.d("Idle watchdog not started haNavigate=${config.haNavigateScreensaver} screenSaver=${config.screenSaver} initialised=$initialised")
            cancelIdleSignal("watchdog-disabled")
            return
        }
        if (config.lastActivity <= 0L) {
            config.lastActivity = System.currentTimeMillis()
        }
        if (forceRestart && idleSignalJob?.isActive == true) {
            cancelIdleSignal("restart-$reason")
        }
        if (idleSignalJob?.isActive == true) {
            log.d("Idle watchdog already active reason=$reason")
            return
        }
        val timeoutMs = maxOf(15_000L, config.screenTimeout.toLong())
        log.d("Idle watchdog started timeoutMs=$timeoutMs screenOn=${config.screenOn} reason=$reason")
        idleSignalJob = lifecycleScope.launch {
            while (true) {
                delay(2_000L)
                if (!shouldRunIdleWatchdog()) {
                    log.d("Idle watchdog stopping haNavigate=${config.haNavigateScreensaver} screenSaver=${config.screenSaver} initialised=$initialised")
                    break
                }
                val currentTimeoutMs = maxOf(15_000L, config.screenTimeout.toLong())
                val idleForMs = System.currentTimeMillis() - config.lastActivity
                config.screenOn = !screen.isScreenOff()
                if (!config.uiIdle && idleForMs >= currentTimeoutMs) {
                    if (config.haNavigateScreensaver) {
                        log.d("Idle signal fired timeoutMs=$currentTimeoutMs idleForMs=$idleForMs")
                        setUiIdle(true, "idle-timeout")
                    } else if (config.screenSaver) {
                        log.d("Local screensaver timeout fired timeoutMs=$currentTimeoutMs idleForMs=$idleForMs")
                        screenSleep()
                        break
                    }
                }
            }
            idleSignalJob = null
        }
    }

    private fun isScreensaverPath(path: String): Boolean {
        val screensaverPath = config.haScreensaverDashboard.trim()
        if (screensaverPath.isBlank()) return false
        return path.startsWith(screensaverPath)
    }

    private fun handleNonScreensaverNavigation(path: String, source: String) {
        if (!config.haNavigateScreensaver || isScreensaverPath(path)) {
            return
        }
        config.lastActivity = System.currentTimeMillis()
        if (config.uiIdle) {
            log.d("Leaving idle due to $source path=$path")
            setUiIdle(false, "$source-$path")
        } else {
            log.d("Rearming idle timer after $source path=$path")
            scheduleIdleSignal(forceRestart = true, reason = "$source-$path")
        }
    }

    private fun cancelIdleSignal(reason: String) {
        if (idleSignalJob?.isActive == true) {
            log.d("Idle watchdog cancelled reason=$reason")
            idleSignalJob?.cancel()
        }
        idleSignalJob = null
    }

    private fun setUiIdle(idle: Boolean, reason: String) {
        if (config.uiIdle == idle) {
            if (idle && config.haNavigateScreensaver) {
                screen.setScreenAlwaysOn(window, true)
                if (screen.isScreenOff()) {
                    screen.wakeScreen(8000)
                }
            }
            return
        }
        config.uiIdle = idle
        log.d("UI idle state -> $idle reason=$reason")
        if (config.haNavigateScreensaver) {
            if (idle) {
                screen.setScreenAlwaysOn(window, true)
                if (screen.isScreenOff()) {
                    screen.wakeScreen(8000)
                }
            } else {
                screen.setScreenAlwaysOn(window, config.screenAlwaysOn)
                scheduleIdleSignal(forceRestart = true, reason = "ui-idle-false-$reason")
            }
        }
    }

    private fun navigateToPath(path: String) {
        val normalizedPath = when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> path
            else -> "/$path"
        }
        val haBaseUrl = AuthUtils.getHAUrl(config, withDashboardPath = false).removeSuffix("/")
        val rawTargetUrl = if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) {
            normalizedPath
        } else {
            "$haBaseUrl$normalizedPath"
        }
        val currentUrl = webView.url.orEmpty()
        val targetIsHomeAssistant = rawTargetUrl.startsWith(haBaseUrl, ignoreCase = true)
        val currentIsHomeAssistant = currentUrl.startsWith(haBaseUrl, ignoreCase = true)

        if (targetIsHomeAssistant && currentIsHomeAssistant && config.accessToken.isNotBlank()) {
            val spaTargetPath = try {
                val parsedTarget = URL(rawTargetUrl)
                val query = if (parsedTarget.query.isNullOrBlank()) "" else "?${parsedTarget.query}"
                val fragment = if (parsedTarget.ref.isNullOrBlank()) "" else "#${parsedTarget.ref}"
                parsedTarget.path + query + fragment
            } catch (_: Exception) {
                normalizedPath
            }
            val escapedSpaPath = spaTargetPath
                .replace("\\", "\\\\")
                .replace("'", "\\'")
            val spaScript = """
                (function() {
                    try {
                        var target = '$escapedSpaPath';
                        var url = new URL(target, window.location.origin);
                        var nextPath = url.pathname + url.search + url.hash;
                        var currentPath = window.location.pathname + window.location.search + window.location.hash;
                        if (url.origin !== window.location.origin) {
                            return "cross-origin";
                        }
                        if (currentPath === nextPath) {
                            return "same";
                        }
                        window.history.pushState(null, "", nextPath);
                        window.dispatchEvent(new CustomEvent("location-changed"));
                        return "spa";
                    } catch (e) {
                        return "error:" + e.message;
                    }
                })();
            """.trimIndent()
            log.d("Navigate action path=$normalizedPath url=$rawTargetUrl mode=spa")
            webView.evaluateJavascript(spaScript) { result ->
                log.d("Navigate SPA result: $result")
                if (result != "\"spa\"" && result != "\"same\"") {
                    log.d("Navigate SPA fallback path=$normalizedPath url=$rawTargetUrl")
                    webView.loadUrl(rawTargetUrl)
                }
            }
            return
        }
        val targetUrl = if (
            targetIsHomeAssistant &&
            !rawTargetUrl.contains("external_auth=") &&
            (!currentIsHomeAssistant || config.accessToken.isBlank())
        ) {
            AuthUtils.getURL(rawTargetUrl)
        } else {
            rawTargetUrl
        }
        log.d("Navigate action path=$normalizedPath url=$targetUrl")
        webView.loadUrl(targetUrl)
    }

    private fun loadStartupUrl() {
        val startupPath = if (config.homeAssistantDashboard.isNotBlank()) {
            "/${config.homeAssistantDashboard.removePrefix("/")}"
        } else {
            "/view-assist/clock"
        }
        val startupUrl = AuthUtils.getURL(
            AuthUtils.getHAUrl(config, withDashboardPath = false).removeSuffix("/") + startupPath
        )
        log.d("Loading startup path: $startupPath")
        log.d("Loading startup url: $startupUrl")
        webView.loadUrl(startupUrl)
    }

    fun setDarkMode(isDark: Boolean) {
        log.d("Setting dark mode: $isDark")

        if (isDark) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else{
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        // Set device dark mode
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            uiModeManager.setApplicationNightMode(if (isDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
        } else {
            uiModeManager.nightMode = if (isDark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        }

        webView.refreshDarkMode()
    }

    private fun updatePermissionStatus() {
        val corePermissions = permissions.hasCorePermissions()
        val optionalPermissions = permissions.hasOptionalPermissions()
        Timber.d("Core permissions: $corePermissions")
        Timber.d("Optional permissions: $optionalPermissions")
        viewModel.setPermissionsStatus(corePermissions, optionalPermissions)
    }

    private fun checkAndRequestPermissions() {
        var requiredPermissions: Array<String> = arrayOf()
        var requestID: Int = 0

        log.d("Checking main permissions")

        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions += permission.RECORD_AUDIO
            requestID += RECORD_AUDIO_PERMISSIONS_REQUEST
        } else {
            config.hasRecordAudioPermission = true
        }

        if (DeviceCapabilitiesManager(this).hasFrontCamera()) {
            if (ContextCompat.checkSelfPermission(this, permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions += permission.CAMERA
                requestID += CAMERA_PERMISSIONS_REQUEST
            } else {
                config.hasCameraPermission = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions += permission.POST_NOTIFICATIONS
                requestID += NOTIFICATION_PERMISSIONS_REQUEST
            } else {
                config.hasPostNotificationPermission = true
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions += permission.WRITE_EXTERNAL_STORAGE
                requestID += WRITE_EXTERNAL_STORAGE_PERMISSIONS_REQUEST
            } else {
                config.hasWriteExternalStoragePermission = true
            }
        }

        if (requiredPermissions.isNotEmpty()) {
            log.d("Requesting main permissions")
            log.d("Permissions: ${requiredPermissions.map { it }}")
            ActivityCompat.requestPermissions(
                this, requiredPermissions, requestID
            )
        } else {
            log.d("Main permissions already granted")
            checkAndRequestWriteSettingsPermission()
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        appPermissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, appPermissions, grantResults)
        if (appPermissions.isNotEmpty()) {
            for (i in appPermissions.indices) {
                if (appPermissions[i] == permission.RECORD_AUDIO && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    log.d("Permission granted: ${appPermissions[i]}")
                    //config.hasRecordAudioPermission = true
                }
                if (appPermissions[i] == permission.POST_NOTIFICATIONS && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    log.d("Permission granted: ${appPermissions[i]}")
                    //config.hasPostNotificationPermission = true
                }
                if (appPermissions[i] == permission.WRITE_EXTERNAL_STORAGE && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    log.d("Permission granted: ${appPermissions[i]}")
                    //config.hasWriteExternalStoragePermission = true
                }
                if (appPermissions[i] == permission.CAMERA && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    log.d("Permission granted: ${appPermissions[i]}")
                    //config.hasCameraPermission = true
                }
            }
        }
        updatePermissionStatus()
        if (permissions.hasCorePermissions()) {
            log.d("Main permissions granted")
        }
        checkAndRequestWriteSettingsPermission()
        /*
        } else {
            log.d("Main permissions not granted will not run background tasks")
            if (!p.hasPermission(Permissions.RECORD_AUDIO)) {
                log.d("Record audio permission not granted")
            }
            if (!p.hasPermission(Permissions.POST_NOTIFICATIONS)) {
                log.d("Post notification permission not granted")
            }
            initialise()
        }

         */
    }

    companion object {
        private const val RECORD_AUDIO_PERMISSIONS_REQUEST = 200
        private const val CAMERA_PERMISSIONS_REQUEST = 250
        private const val NOTIFICATION_PERMISSIONS_REQUEST = 300
        private const val WRITE_EXTERNAL_STORAGE_PERMISSIONS_REQUEST = 400
    }

    private val onWriteSettingsPermissionActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updatePermissionStatus()
        checkAndRequestNotificationAccessPolicyPermission()
    }

    private fun checkAndRequestWriteSettingsPermission() {
        if (config.canSetScreenWritePermission && !ScreenUtils(this).canWriteScreenSetting()) {
            val alertDialog = AlertDialog.Builder(this)
            log.d("Requesting write settings permission")
            alertDialog.apply {
                setTitle("Write Settings Permission Required")
                setMessage("This application needs this permission to control the Auto brightness setting.  If your device requires explicit permission, the screen will launch for you to enable it.")
                setPositiveButton("Got it") { _: DialogInterface?, _: Int ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        onWriteSettingsPermissionActivityResult.launch(intent)
                    } catch (e: Exception) {
                        log.i("Device does not require explicit permission")
                        config.canSetScreenWritePermission = false
                        checkAndRequestNotificationAccessPolicyPermission()
                    }
                }
            }.create().show()
        } else {
            log.d("Write settings permission ${if (!config.canSetScreenWritePermission) "not required" else "already granted"}")
            checkAndRequestNotificationAccessPolicyPermission()
        }
    }

    private val onNotificationAccessPolicyPermissionActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        log.i("Notification access policy permission result -> ${it.resultCode}")
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
            log.d("Requesting notification access policy permission")
            alertDialog.apply {
                setTitle("Notification Policy Access Permission Required")
                setMessage("This application needs this permission to control the Do Not Disturb setting.  If your device has this capability and requires explicit permission, the screen will launch for you to enable it.")
                setPositiveButton("Got it") { _: DialogInterface?, _: Int ->
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        onNotificationAccessPolicyPermissionActivityResult.launch(intent)
                    } catch (e: Exception) {
                        log.i("Device does not require explicit permission")
                        checkAndRequestDeviceAdminPermission()
                    }
                }
            }.create().show()
        } else {
            log.d("Notification access policy permission already granted or not supported")
            config.hasPostNotificationPermission = true
            checkAndRequestDeviceAdminPermission()
        }
    }

    private val onDeviceAdminPermissionActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        log.i("Device Admin permission result -> ${it.resultCode}")
        updatePermissionStatus()
        initialise()
    }

    private fun checkAndRequestDeviceAdminPermission() {
        if (!isAndroidThings(this) && !permissions.isDeviceAdmin()) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, VACADeviceAdminReceiver::class.java))
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "This application requires Device Admin rights to be able to control the screen.")
            onDeviceAdminPermissionActivityResult.launch(intent)
        } else {
            initialise()
        }
    }

    private fun checkForUpdate() {
        try {
            Timber.d("Checking for update")
            if (updater.isUpdateAvailable(config.minRequiredApkVersion)) {
                log.d("Update available - ${updater.latestRelease.downloadURL}")

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

    private fun downloadAndInstallUpdate() {
        setStatus(getString(R.string.status_downloading_update))
        updater.requestDownload { uri ->
            if (uri != "") {
                log.d("Download complete = $uri")
                setStatus(getString(R.string.status_installing_update))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                    intent.setData(uri.toUri())
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    onUpdateAppActivityResult.launch(intent)
                } else {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.setDataAndType(
                        uri.toUri(),
                        "application/vnd.android.package-archive"
                    );
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
            firebase.logEvent(FirebaseManager.TRIM_MEMORY_UI_HIDDEN, mapOf())
            Runtime.getRuntime().gc()

        }

        if (level >= TRIM_MEMORY_BACKGROUND) {
            // Release memory related to background processing, such as by
            // closing a database connection.
            firebase.logEvent(FirebaseManager.TRIM_MEMORY_BACKGROUND, mapOf())
            Runtime.getRuntime().gc()
        }

        super.onTrimMemory(level)
    }
}

