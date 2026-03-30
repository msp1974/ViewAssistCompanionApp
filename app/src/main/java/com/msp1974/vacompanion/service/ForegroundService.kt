package com.msp1974.vacompanion.service

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.msp1974.vacompanion.MainActivity
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.VACAApplication
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.BackgroundTaskStatus
import com.msp1974.vacompanion.utils.FirebaseManager
import kotlinx.coroutines.launch
import timber.log.Timber


class VAForegroundService : LifecycleService() {
    companion object {
        const val SERVICE_NOTIFICATION_ID = 1
        const val RECOVERY_NOTIFICATION_ID = 2
        private const val RECOVERY_WATCHDOG_INTERVAL_MS = 5000L
        private const val RECOVERY_REQUEST_COOLDOWN_MS = 15000L
    }

    private lateinit var config: APPConfig
    private lateinit var firebase: FirebaseManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var keyguardLock: KeyguardManager.KeyguardLock? = null
    private lateinit var notificationManager: NotificationManager

    private var backgroundTask:  BackgroundTaskController? = null
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var lastRecoveryRequestMs = 0L
    private val recoveryWatchdog = object : Runnable {
        override fun run() {
            try {
                val activity = VACAApplication.activityManager.activity
                if (config.backgroundTaskRunning && activity == null) {
                    val now = System.currentTimeMillis()
                    if (now - lastRecoveryRequestMs >= RECOVERY_REQUEST_COOLDOWN_MS) {
                        Timber.w("Foreground activity missing while service is active. Requesting recovery.")
                        requestActivityRecovery()
                    }
                }
            } finally {
                recoveryHandler.postDelayed(this, RECOVERY_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    enum class Actions {
        START, STOP
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        config = APPConfig.getInstance(this)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        firebase = FirebaseManager.getInstance(this)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

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

    /**
    * Main process for the service
    * */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        var action = intent?.action ?: Actions.START.toString()
        val restartedByOs = intent == null
        Timber.v("onStartCommand action: $action")
        if (intent == null) {
            Timber.v("VACA restarted by OS after crash")
            action = Actions.START.toString()
        }
        // Do the work that the service needs to do here
        when (action) {
            Actions.START.toString() -> {
                if (!checkIfPermissionIsGranted()) return START_STICKY
                val notification =
                    buildServiceNotification(launchActivity = false)

                lifecycleScope.launch {
                    firebase.addToCrashLog("Background service starting")
                    try {
                        //need core 1.12 and higher and SDK 30 and higher
                        var requires: Int = 0
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            requires += ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            requires += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            requires += ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        }

                        Timber.d("Running in foreground ServiceCompat mode")
                        ServiceCompat.startForeground(
                            this@VAForegroundService,
                            SERVICE_NOTIFICATION_ID,
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
                        }

                        backgroundTask = BackgroundTaskController(this@VAForegroundService)
                        backgroundTask?.start()
                        Timber.i("Background Service Started")
                        config.backgroundTaskRunning = true
                        config.backgroundTaskStatus = BackgroundTaskStatus.STARTED
                        startRecoveryWatchdog()
                        if (restartedByOs && VACAApplication.activityManager.activity == null) {
                            requestActivityRecovery()
                        } else {
                            notificationManager.cancel(RECOVERY_NOTIFICATION_ID)
                            notificationManager.notify(
                                SERVICE_NOTIFICATION_ID,
                                buildServiceNotification(launchActivity = false)
                            )
                        }
                    } catch (ex: Exception) {
                        Timber.e(ex, "Foreground service startup failed")
                        config.backgroundTaskRunning = false
                        config.backgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED
                        firebase.logException(ex)
                    }
                }
            }

            Actions.STOP.toString() -> {
                firebase.addToCrashLog("Background service stopping")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startActivity(context: Context) {
        try {
            val myIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            }
            context.startActivity(myIntent)
        } catch (ex: Exception) {
            Timber.e("Watchdog failed to restart activity - ${ex.message}")
        }
    }

    private fun activityPendingIntent(homeIntent: Boolean = false): PendingIntent {
        val intent = if (homeIntent) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 1, intent, flags)
    }

    private fun buildServiceNotification(launchActivity: Boolean): Notification {
        val channelId =
            if (launchActivity) VACAApplication.RECOVERY_CHANNEL_ID
            else VACAApplication.FOREGROUND_CHANNEL_ID
        val builder =
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("View Assist Companion App")
                .setContentText(
                    if (launchActivity) {
                        "Recovering app display"
                    } else {
                        "Service is running"
                    }
                )
                .setContentIntent(activityPendingIntent(homeIntent = launchActivity))
                .setPriority(
                    if (launchActivity) NotificationCompat.PRIORITY_MAX
                    else NotificationCompat.PRIORITY_LOW
                )
                .setCategory(
                    if (launchActivity) NotificationCompat.CATEGORY_CALL
                    else NotificationCompat.CATEGORY_SERVICE
                )
                .setOngoing(true)
                .addAction(
                    R.drawable.outline_stop_circle_24, getString(R.string.stop_service),
                    stopServiceIntent(Actions.STOP.toString())
                )

        if (launchActivity) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            builder.setFullScreenIntent(activityPendingIntent(homeIntent = true), true)
            builder.setAutoCancel(false)
        }
        return builder.build()
    }

    private fun requestActivityRecovery() {
        try {
            lastRecoveryRequestMs = System.currentTimeMillis()
            Timber.i("Requesting activity recovery via full-screen notification")
            notificationManager.notify(
                RECOVERY_NOTIFICATION_ID,
                buildServiceNotification(launchActivity = true)
            )
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + 1000
            val activityIntent = activityPendingIntent(homeIntent = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    activityIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, activityIntent)
            }
            startActivity(this)
        } catch (ex: Exception) {
            Timber.e("Failed to request activity recovery - ${ex.message}")
        }
    }

    private fun startRecoveryWatchdog() {
        recoveryHandler.removeCallbacks(recoveryWatchdog)
        recoveryHandler.postDelayed(recoveryWatchdog, RECOVERY_WATCHDOG_INTERVAL_MS)
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
        super.onDestroy()
        Timber.i("Stopping Background Service")
        backgroundTask?.shutdown()
        config.backgroundTaskRunning = false
        config.backgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED
        recoveryHandler.removeCallbacks(recoveryWatchdog)
        notificationManager.cancel(RECOVERY_NOTIFICATION_ID)

        // Release any lock from this app
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
        try {
            keyguardLock!!.reenableKeyguard()
        } catch (ex: Exception) {
            Timber.i("Enabling keyguard didn't work")
            ex.printStackTrace()
            firebase.logException(ex)
        }
    }

}
