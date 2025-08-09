package com.msp1974.vacompanion.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.NotificationUtils


class VABackgroundService : Service() {
    private val log = Logger()
    private var wifiLock: WifiManager.WifiLock? = null
    private var keyguardLock: KeyguardManager.KeyguardLock? = null

    private val backgroundTask = BackgroundTaskController(this)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // wifi lock
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "vacompanion.VABackgroundService:wifiLock")
        // Some Amazon devices are not seeing this permission so we are trying to check
        val permission = "android.permission.DISABLE_KEYGUARD"
        val checkSelfPermission = ContextCompat.checkSelfPermission(this@VABackgroundService, permission)
        if (checkSelfPermission == PackageManager.PERMISSION_GRANTED) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardLock = keyguardManager.newKeyguardLock("ALARM_KEYBOARD_LOCK_TAG")
            keyguardLock!!.disableKeyguard()
        }
        // Set notification
        notificationService()
    }

    /**
     * The Notification is mandatory for background services
     * */
    private fun notificationService() {
        val notificationUtils = NotificationUtils(applicationContext, application.resources)
        val notification = notificationUtils.createNotification(getString(R.string.service_notification_title), getString(R.string.service_notification_message))

        //need core 1.12 and higher and SDK 30 and higher
        var requires: Int = 0
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            requires += ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            requires += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            log.d("Running in foreground ServiceCompat mode")
            ServiceCompat.startForeground(
                this@VABackgroundService, 1, notification,
                requires
            )
        } else {
            log.d("Running in foreground service")
            startForeground(1, notification)
        }
    }

    /**
     * Main process for the service
     * */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!checkIfPermissionIsGranted()) return START_NOT_STICKY
        if (!wifiLock!!.isHeld) {
            wifiLock!!.acquire()
        }
        try {
            keyguardLock?.disableKeyguard()
        } catch (ex: Exception) {
            log.i("Disabling keyguard didn't work")
            ex.printStackTrace()
        }

        backgroundTask.start()
        log.i("Background Service Started")
        return START_STICKY
    }

    private fun checkIfPermissionIsGranted() = ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED /*&& ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED*/

    override fun onDestroy() {
        log.i("Stopping Background Service")
        backgroundTask.shutdown()

        // Release any lock from this app
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
        try {
            keyguardLock!!.reenableKeyguard()
        } catch (ex: Exception) {
            log.i("Enabling keyguard didn't work")
            ex.printStackTrace()
        }
    }

}