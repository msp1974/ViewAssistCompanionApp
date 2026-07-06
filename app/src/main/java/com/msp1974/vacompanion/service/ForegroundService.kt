package com.msp1974.vacompanion.service

import android.Manifest
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.msp1974.vacompanion.MainActivity
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.VACAApplication
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.data.NetworkStatusManager
import com.msp1974.vacompanion.settings.BackgroundTaskStatus
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.device.DeviceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Timer
import java.util.TimerTask
import javax.inject.Inject

@AndroidEntryPoint
class VAForegroundService @Inject constructor() : LifecycleService() {

    @Inject lateinit var deviceManager: DeviceManager

    private val config get() = deviceManager.config

    private lateinit var firebase: FirebaseManager
    private var keyguardLock: KeyguardManager.KeyguardLock? = null
    private var watchdogTimer: Timer = Timer()

    private var backgroundTask:  BackgroundTaskController? = null

    enum class Actions {
        START, STOP
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        firebase = FirebaseManager.getInstance(this)

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
        Timber.v("onStartCommand action: $action")
        if (intent == null) {
            Timber.v("VACA restarted by OS after crash")
            startActivity(this)
            action = Actions.START.toString()
        }
        // Do the work that the service needs to do here
        when (action) {
            Actions.START.toString() -> {
                if (!checkIfPermissionIsGranted()) return START_STICKY
                val notification =
                    NotificationCompat.Builder(this, "VACAForegroundServiceChannelId")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("View Assist Companion App")
                        .setContentText("Service is running")
                        .apply {
                            if (isHomeApp()) {
                                addAction(
                                    R.drawable.outline_stop_circle_24, getString(R.string.close_app),
                                    sendServiceIntent(Actions.STOP)
                                )
                            }
                        }
                        .build()



                lifecycleScope.launch {
                    firebase.addToCrashLog("Background service starting")

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
                        1,
                        notification,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            requires
                        } else {
                            0
                        },
                    )

                    try {
                        keyguardLock?.disableKeyguard()
                    } catch (ex: Exception) {
                        Timber.i("Disabling keyguard didn't work")
                        ex.printStackTrace()
                        firebase.logException(ex)
                    }

                    backgroundTask = BackgroundTaskController(this@VAForegroundService, deviceManager)
                    backgroundTask?.start()
                    Timber.i("Background Service Started")
                    config.backgroundTaskRunning = true
                    config.backgroundTaskStatus = BackgroundTaskStatus.STARTED

                    // Launch Activity if not running on service start
                    // Can be caused by crash and service restarted by OS
                    //if (config.currentActivity == "") {
                    //    Timber.i("Launching MainActivity from foreground service")
                    //    Firebase.crashlytics.log("Launching MainActivity from foreground service")
                    //    val intent = Intent(this, MainActivity::class.java)
                    //    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    //    try {
                    //        startActivity(intent)
                    //    } catch (ex: Exception) {
                    //        Timber.e("Foreground service failed to launch activity - ${ex.message}")
                    //    }
                    //}
                    //restartActivityWatchdog()
                }
            }

            Actions.STOP.toString() -> {
                Timber.d("Stopping foreground service")
                BroadcastSender.sendBroadcast(this, BroadcastSender.CLOSE_APP)
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
                if (VACAApplication.activityManager.activity == null) {
                    Timber.d("Watchdog detected activity not running.  Restarting...")
                    startActivity(this@VAForegroundService)
                }
            }
        },0,5000)
    }

    private fun sendServiceIntent(action: Actions): PendingIntent {
        val intent = Intent(this, VAForegroundService::class.java)
        intent.setAction(action.toString())
        val pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return pendingIntent
    }

    private fun checkIfPermissionIsGranted() = ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.i("onTaskRemoved - stopping service")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("Stopping Background Service")
        watchdogTimer.cancel()
        backgroundTask?.shutdown()

        config.backgroundTaskRunning = false
        config.backgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED

        try {
            keyguardLock!!.reenableKeyguard()
        } catch (ex: Exception) {
            Timber.i("Enabling keyguard didn't work")
            ex.printStackTrace()
            firebase.logException(ex)
        }
    }

    fun isHomeApp(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val res: ResolveInfo? = packageManager.resolveActivity(intent, 0)
        if (res?.activityInfo != null && packageName.equals(res.activityInfo.packageName)
        ) {
            return true
        }
        return false
    }
}
