package com.msp1974.vacompanion

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.service.VABackgroundService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.ScreenUtils


class MainActivity : AppCompatActivity() {
    private lateinit var config: APPConfig
    private val log = Logger()

    private lateinit var screen: ScreenUtils
    private var screenOrientation: Int = 0

    @SuppressLint("HardwareIds", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_waiting)
        this.enableEdgeToEdge()

        config = APPConfig.getInstance(this)
        screen = ScreenUtils(this)
        screenOrientation = resources.configuration.orientation



        if (!isTaskRoot) {
            log.i("MainActivity is not root")
            finish();
            return;
        }
        log.i("#################################################################################################")
        log.i("Starting View Assist Companion App")
        log.i("Version ${config.version}")
        log.i("Android version: ${Helpers.getAndroidVersion()}")
        log.i("Name: ${Helpers.getDeviceName()}")
        log.i("Serial: ${Build.SERIAL}")
        log.i("UUID: ${config.uuid}")
        log.i("#################################################################################################")

        // Show info on screen
        val logo = findViewById<ImageView>(R.id.vaLogo)
        val ip = findViewById<TextView>(R.id.ip)
        val version = findViewById<TextView>(R.id.version)
        val uuid = findViewById<TextView>(R.id.uuid)
        val pairedDevice = findViewById<TextView>(R.id.paired_with)
        val startOnBoot = findViewById<SwitchCompat>(R.id.startOnBoot)

        setScreenLayout()

        version.text = config.version
        ip.text = Helpers.getIpv4HostAddress()
        uuid.text = config.uuid

        if (config.pairedDeviceID != "") {
            pairedDevice.text = config.pairedDeviceID
        }

        // Long press on paired device id to clear pairing
        logo.setOnLongClickListener {
            if (config.pairedDeviceID != "") {
                val alertDialog = AlertDialog.Builder(this)
                alertDialog.apply {
                    setTitle("Clear Paired Device Entry")
                    setMessage("This will delete the currently paired Home Assistant server and allow another server to connect and pair to this device.")
                    setPositiveButton("Confirm") { _: DialogInterface?, _: Int ->
                        config.accessToken = ""
                        config.pairedDeviceID = ""
                        pairedDevice.text = "Not paired"
                    }
                    setNegativeButton("Cancel") { _: DialogInterface?, _: Int -> }
                }.create().show()
            }
            true
        }

        startOnBoot.setChecked(config.startOnBoot)
        startOnBoot.setOnCheckedChangeListener({ _, isChecked ->
            config.startOnBoot = isChecked
        })

        // Check and get required user permissions
        log.d("Checking permissions")
        checkAndRequestPermissions()

    }

    fun initialise() {
        if (!hasPermissions()) {
            findViewById<TextView>(R.id.status_message).text = "No permissions"
            return
        }
        var hasNetwork = Helpers.isNetworkAvailable(this)
        if (!this.hasWindowFocus() || !hasNetwork) {
            if (!hasNetwork) {
                findViewById<TextView>(R.id.status_message).text = "Waiting for network..."
            }
            Handler(Looper.getMainLooper()).postDelayed({
                initialise()
            }, 1000)
            return
        }

        findViewById<TextView>(R.id.ip).text = Helpers.getIpv4HostAddress()
        findViewById<TextView>(R.id.status_message).text = "Waiting for connection..."

        // Initiate wake word broadcast receiver
        var satelliteBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (config.currentActivity != "WebViewActivity") {
                    runWebViewIntent()
                }
            }
        }
        val filter = IntentFilter().apply { addAction(BroadcastSender.SATELLITE_STARTED) }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(satelliteBroadcastReceiver, filter)

        // Start background tasks
        runBackgroundTasks()
    }

    fun setScreenLayout() {
        val imageView = findViewById<ImageView>(R.id.vaLogo)
        val orientation = resources.configuration.orientation
        val screenHeight: Int = Resources.getSystem().displayMetrics.widthPixels;
        val screenWidth: Int = Resources.getSystem().displayMetrics.heightPixels;

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            imageView.layoutParams.height = (screenHeight * 0.3).toInt()
            imageView.layoutParams.width = screenWidth.coerceAtMost((imageView.layoutParams.height * 1.6).toInt())
        } else {
            imageView.layoutParams.width = (screenHeight * 0.85).toInt()
            imageView.layoutParams.height = (imageView.layoutParams.width * 0.6).toInt()
        }

        log.i("Screen: w$screenWidth h$screenHeight o$orientation, Logo: w${imageView.layoutParams.width} h${imageView.layoutParams.height}")
    }

    // Listening to the orientation config
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != screenOrientation) {
            setScreenLayout()
        }
    }

    override fun onResume() {
        super.onResume()
        log.d("Main Activity resumed")
        if (screen.isScreenOn() && config.currentActivity != "WebViewActivity" && config.isRunning) {
            log.d("Resuming webView activity")
            runWebViewIntent()
        }
    }

    @SuppressLint("Wakelock")
    override fun onDestroy() {
        log.d("Main Activity destroyed")
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            log.d("Main Activity window focus changed")
            config.currentActivity = "MainActivity"
            // Hide status and action bars
            ScreenUtils(this).hideStatusAndActionBars()
            val pairedWith = findViewById<TextView>(R.id.paired_with)
            if (config.pairedDeviceID != "") {
                pairedWith.text = config.pairedDeviceID
            }
        }
    }

    private fun runBackgroundTasks() {
        log.d("Starting background tasks")
        val serviceIntent = Intent(this.applicationContext, VABackgroundService::class.java)
        startService(serviceIntent)
    }

    private fun runWebViewIntent() {
        log.d("Loading WebView activity")
        val webIntent = Intent(this@MainActivity, WebViewActivity::class.java)
        webIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(webIntent)
    }

    private fun hasPermissions(): Boolean {
        val result: Boolean = config.hasRecordAudioPermission && config.hasPostNotificationPermission
        return result
    }

    private fun checkAndRequestPermissions() {
        var requiredPermissions: Array<String> = arrayOf()
        var requestID: Int = 0

        log.d("Checking main permissions")

        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions += permission.RECORD_AUDIO
            requestID += RECORD_AUDIO_PERMISSIONS_REQUEST
            config.hasRecordAudioPermission = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions += permission.POST_NOTIFICATIONS
                requestID += NOTIFICATION_PERMISSIONS_REQUEST
                config.hasPostNotificationPermission = false
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
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissions.isNotEmpty()) {
            for (i in permissions.indices) {
                if (permissions[i] == permission.RECORD_AUDIO && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    log.d("Permission granted: ${permissions[i]}")
                    config.hasRecordAudioPermission = true
                }
                if (permissions[i] == permission.POST_NOTIFICATIONS && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    log.d("Permission granted: ${permissions[i]}")
                    config.hasPostNotificationPermission = true
                }
            }
        }
        if (hasPermissions()) {
            log.d("Main permissions granted")
            checkAndRequestWriteSettingsPermission()
        } else {
            log.d("Main permissions not granted will not run background tasks")
            if (!config.hasRecordAudioPermission) {
                log.d("Record audio permission not granted")
            }
            if (!config.hasPostNotificationPermission) {
                log.d("Post notification permission not granted")
            }
            initialise()
        }
    }

    companion object {
        private const val RECORD_AUDIO_PERMISSIONS_REQUEST = 200
        private const val NOTIFICATION_PERMISSIONS_REQUEST = 300
    }

    private fun checkAndRequestWriteSettingsPermission() {
        log.d("Checking write settings permission")
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
                        startActivity(intent)
                        initialise()
                        //onWriteSettingsPermissionActivityResult.launch(intent)
                    } catch (e: Exception) {
                        log.i("Device does not require explicit permission")
                        config.canSetScreenWritePermission = false
                        initialise()
                    }
                }
            }.create().show()
        } else {
            log.d("Write settings permission ${if (!config.canSetScreenWritePermission) "not required" else "granted"}")
            initialise()
        }
    }

}

