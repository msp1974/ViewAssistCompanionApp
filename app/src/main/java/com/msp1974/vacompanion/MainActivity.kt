package com.msp1974.vacompanion

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.sensors.Sensors
import com.msp1974.vacompanion.service.VABackgroundService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.ScreenUtils


class MainActivity : AppCompatActivity() {
    private var recordPermissions: Boolean = false
    private lateinit var config: APPConfig
    private val log = Logger()

    private var sensors: Sensors? = null

    @SuppressLint("HardwareIds", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_waiting)
        this.enableEdgeToEdge()

        config = APPConfig.getInstance(this)

        if (!isTaskRoot) {
            log.i("MainActivity is not root")
            finish();
            return;
        }
        log.i("MainActivity is root")

        log.i("#################################################################################################")
        log.i("Starting View Assist Companion App")
        log.i("Version ${config.version}")
        log.i("Android version: ${getAndroidVersion()}")
        log.i("Name: ${getDeviceName()}")
        log.i("Serial: ${Build.SERIAL}")
        log.i("UUID: ${config.uuid}")
        log.i("#################################################################################################")

        // Show info on screen
        val logo = findViewById<ImageView>(R.id.vaLogo)
        val ip = findViewById<TextView>(R.id.ip)
        val version = findViewById<TextView>(R.id.version)
        val uuid = findViewById<TextView>(R.id.uuid)
        val startOnBoot = findViewById<SwitchCompat>(R.id.startOnBoot)
        ip.text = "IP: " + Helpers.getIpv4HostAddress()
        version.text = "Version: " + config.version
        uuid.text = "ID: " + config.uuid
        startOnBoot.setChecked(config.startOnBoot)
        startOnBoot.setOnCheckedChangeListener({ _, isChecked ->
            config.startOnBoot = isChecked
        })

        // Check and get user permission to record
        log.d("Checking permissions")
        checkAndRequestPermissions()

    }

    fun initialise() {

        val mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val deviceSensors = mSensorManager.getSensorList(Sensor.TYPE_ALL)
        for (i in 0..deviceSensors.size -1) {
            log.i("Sensor: ${deviceSensors.get(i)}")
            //if (deviceSensors.get(i)!!.getType() === Sensor.TYPE_PRESSURE) {
            //    mHasBarometer = true
            //    break
            //}
        }
        sensors = Sensors(this)
        sensors?.registerLightSensorListener()
        log.i("Orientation: ${sensors?.orientation}")

        // Initiate wake word broadcast receiver
        var satelliteBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (config.currentActivity != "WebViewActivity") {
                    runWebViewIntent()
                }
            }
        }
        val filter = IntentFilter().apply { addAction(BroadcastSender.SATELLITE_STARTED) }
        LocalBroadcastManager.getInstance(this).registerReceiver(satelliteBroadcastReceiver, filter)

        // Start background tasks
        runBackgroundTasks()
    }

    fun getAndroidVersion(): String {
        val release = Build.VERSION.RELEASE
        val sdkVersion = Build.VERSION.SDK_INT
        return "SDK: $sdkVersion ($release)"
    }

    @SuppressLint("HardwareIds")
    fun getDeviceName(): String? {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        if (model.lowercase().startsWith(manufacturer.lowercase())) {
            return model
        } else {
            return "$manufacturer $model"
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
            ScreenUtils(this).hideStatusAndActionBars()
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

        log.d("Checking main permissions")

        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions += permission.RECORD_AUDIO
            config.hasRecordAudioPermission = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions += permission.POST_NOTIFICATIONS
                config.hasPostNotificationPermission = false
            }
        }

        if (requiredPermissions.isNotEmpty()) {
            log.d("Requesting main permissions")
            ActivityCompat.requestPermissions(
                this, requiredPermissions, MAIN_PERMISSIONS_REQUEST
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
        if (requestCode == MAIN_PERMISSIONS_REQUEST) {
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
            }
        }
    }

    companion object {
        private const val MAIN_PERMISSIONS_REQUEST = 200
    }

    private fun checkAndRequestWriteSettingsPermission() {
        log.d("Checking write settings permission")
        if (config.canSetScreenWritePermission && !ScreenUtils(this).canWriteScreenSetting()) {
            val alertDialog = AlertDialog.Builder(this)
            log.d("Requesting write settings permission")
            alertDialog.apply {
                setTitle("Write Settings Permission Required")
                setMessage("This application needs permission to modify your systems settings to support the Auto brightness setting.  If your device requires explicit permission, please enable this on the next screen.")
                setPositiveButton("Got it") { _: DialogInterface?, _: Int ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        onWriteSettingsPermissionActivityResult.launch(intent)
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

    private val onWriteSettingsPermissionActivityResult =
        registerForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                log.d("Write settings permission granted")
                initialise()
            }
        }

}

