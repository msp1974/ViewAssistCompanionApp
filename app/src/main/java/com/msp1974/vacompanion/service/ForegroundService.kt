package com.msp1974.vacompanion.service

import android.Manifest
import android.app.KeyguardManager
import android.app.PendingIntent
//MH
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import com.msp1974.vacompanion.BuildConfig
//MH-end
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.msp1974.vacompanion.MainActivity
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.VACAApplication
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.BackgroundTaskStatus
import com.msp1974.vacompanion.utils.Logger
import timber.log.Timber
import java.util.Timer
import java.util.TimerTask

class VAForegroundService : Service() {
    private lateinit var config: APPConfig
    private var wifiLock: WifiManager.WifiLock? = null
    private var keyguardLock: KeyguardManager.KeyguardLock? = null
    private var watchdogTimer: Timer = Timer()

    private var backgroundTask:  BackgroundTaskController? = null

    enum class Actions {
        START, STOP
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        config = APPConfig.getInstance(this)

        // wifi lock
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "vacompanion.VABackgroundService:wifiLock")
        // Some Amazon devices are not seeing this permission so we are trying to check
        val permission = "android.permission.DISABLE_KEYGUARD"
        val checkSelfPermission = ContextCompat.checkSelfPermission(this@VAForegroundService, permission)
        if (checkSelfPermission == PackageManager.PERMISSION_GRANTED) {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardLock = keyguardManager.newKeyguardLock("ALARM_KEYBOARD_LOCK_TAG")
            keyguardLock!!.disableKeyguard()
        }
    }

    //MH
    private fun createNotification(): Notification {
        val channelId = "va_service_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "VA Service", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("VACA Background Service")
            .setContentText("Running background tasks...")
            .setSmallIcon(R.drawable.splash_image)
            .build()
    }
    //MH-end

    /**
     * Main process for the service
     * */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var action = intent?.action ?: Actions.START.toString()
        Timber.v("onStartCommand action: $action")
        if (intent == null) {
            Timber.v("VACA restarted by OS after crash")
            startActivity(this)
            action = Actions.START.toString()
        }
        // Do the work that the service needs to do here
        when (action) {
            Actions.START.toString() -> {
                Firebase.crashlytics.log("Background service starting")

                //MH
                if (!BuildConfig.AUDIO_INPUT_DISABLED && !checkIfPermissionIsGranted()) {
                    return START_NOT_STICKY
                }
                //if (!checkIfPermissionIsGranted()) return START_STICKY
                //MH-end

                // Fix: Conditionally apply foreground service types using bitwise OR (safer than +=)
                var requires: Int = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requires = requires or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Only request the microphone foreground service type if the flag allows it
                    if (!BuildConfig.AUDIO_INPUT_DISABLED) {
                        requires = requires or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                    requires = requires or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }

                val notification =
                    NotificationCompat.Builder(this, "VACAForegroundServiceChannelId")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("View Assist Companion App")
                        .setContentText("Service is running")
                        .addAction(
                            R.drawable.outline_stop_circle_24, getString(R.string.stop_service),
                            stopServiceIntent(Actions.STOP.toString())
                        )
                        .build()

                Timber.d("Running in foreground ServiceCompat mode")
                ServiceCompat.startForeground(
                    this@VAForegroundService,
                    1,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        requires
                    } else {
                        0
                    },
                )

                if (!wifiLock!!.isHeld) {
                    wifiLock!!.acquire()
                }
                try {
                    keyguardLock?.disableKeyguard()
                } catch (ex: Exception) {
                    Timber.i("Disabling keyguard didn't work")
                    ex.printStackTrace()
                    Firebase.crashlytics.recordException(ex)
                }
                backgroundTask = BackgroundTaskController(this)
                backgroundTask?.start()
                Timber.i("Background Service Started")
                config.backgroundTaskRunning = true
                config.backgroundTaskStatus = BackgroundTaskStatus.STARTED

                // Launch Activity if not running on service start
                // Can be caused by crash and service restarted by OS
                if (config.currentActivity == "") {
                    Timber.i("Launching MainActivity from foreground service")
                    Firebase.crashlytics.log("Launching MainActivity from foreground service")
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    try {
                        startActivity(intent)
                    } catch (ex: Exception) {
                        Timber.e("Foreground service failed to launch activity - ${ex.message}")
                    }
                }
                restartActivityWatchdog()
            }
            Actions.STOP.toString() -> {
                Firebase.crashlytics.log("Background service stopping")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startActivity(context: Context) {
        try {
            val myIntent = Intent(context, MainActivity::class.java)
            myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(myIntent)
        } catch (ex: Exception) {
            Timber.e("Watchdog failed to restart activity - ${ex.message}")
        }
    }

    private fun restartActivityWatchdog() {
        watchdogTimer.schedule(object: TimerTask() {
            override fun run() {
                //MH
                //if (VACAApplication.activityManager.activity == null) {
                if (VACAApplication.activityManager.activity == null && config.currentActivity == "") { //MH-end
                    Timber.d("Watchdog detected activity not running.  Restarting...")
                    startActivity(this@VAForegroundService)
                }
            }
        },0,5000)
    }

    private fun stopServiceIntent(name: String): PendingIntent {
        val intent = Intent(this, VAForegroundService::class.java)
        intent.setAction(name)
        val pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return pendingIntent
    }

    private fun checkIfPermissionIsGranted() = ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        Timber.i("Stopping Background Service")
        watchdogTimer.cancel()
        backgroundTask?.shutdown()
        config.backgroundTaskRunning = false
        config.backgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED

        // Release any lock from this app
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
        try {
            keyguardLock!!.reenableKeyguard()
        } catch (ex: Exception) {
            Timber.i("Enabling keyguard didn't work")
            ex.printStackTrace()
            Firebase.crashlytics.recordException(ex)
        }
    }
}