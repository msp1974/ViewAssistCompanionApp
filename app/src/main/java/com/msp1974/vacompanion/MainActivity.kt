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
            screen.setScreenAlwaysOn(window, config.screenAlwaysOn)
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
                    config.screenOn = screen.isScreenOn()
                    val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
                    log.d("Loading URL: $url")
                    webView.loadUrl(url)
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
                    val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
                    log.d("Loading URL: $url")
                    webView.loadUrl(url)
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (initialised) {
                        // If woken by hardware buttons set screen config
                        setScreenSettings()
                    }
                    config.screenOn = true
                }
                Intent.ACTION_SCREEN_OFF -> {
                    config.screenOn = false
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
                val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
                log.d("Loading URL: $url")
                webView.loadUrl(url)
            } else {
                setStatus(getString(R.string.status_waiting_for_connection))
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
        Timber.d("Initialised")
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        runOnUiThread {
            if (!screenOffInProgress) {
                when (event.eventName) {
                    "screenAlwaysOn" -> {
                        val enabled = event.newValue as Boolean
                        //if (enabled) {
                            //screenWake()
                        //}
                        screen.setScreenAlwaysOn(window, enabled)
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
                    "screenTimeout" -> screen.setScreenTimeout(config.screenTimeout)
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
                "screenSaver" -> screenSaver(event.newValue as Boolean)
                "screenOrientationMode" -> setScreenOrientation(event.newValue as String)
                "deviceBump" -> if (config.screenOnBump) screenWake()
                "proximity" -> if (config.screenOnProximity && event.newValue as Float == 0f) screenWake()
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
        if (config.screenOnMotion) screenWake()
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
            screen.setScreenAlwaysOn(window, true)
            screen.setScreenAutoBrightness(window, false)
            screen.setScreenBrightness(window, 0.01f)
        } else {
            Timber.d("Disabling screen saver")
            viewModel.setScreenBlank(false)
            screen.setScreenAlwaysOn(window, config.screenAlwaysOn)
            screen.setScreenAutoBrightness(window, config.screenAutoBrightness)
            screen.setScreenBrightness(window, config.screenBrightness)
        }
    }

    fun setScreenSaver(active: Boolean) {
        screenSaver(active)
        config.screenSaver = active
    }

    fun screenWake() {
        Timber.d("Wake screen")
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

        if (viewModel.vacaState.value.screenBlank && initialised) {
            setScreenSaver(false)
        }
    }

    fun screenSleep() {
        Timber.d("Sleeping screen")
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
        setScreenSaver(false)
        log.d("Screen off")
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

