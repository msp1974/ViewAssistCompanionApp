package com.msp1974.vacompanion

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import com.msp1974.vacompanion.utils.ActivityManager
import timber.log.Timber
import timber.log.Timber.DebugTree

class VACAApplication: Application() {
    companion object {
        const val FOREGROUND_CHANNEL_ID = "VACAForegroundServiceChannelId"
        const val RECOVERY_CHANNEL_ID = "VACAForegroundRecoveryChannelId"
        lateinit var activityManager: ActivityManager
    }

    override fun onCreate() {
        super.onCreate()

        activityManager = ActivityManager(this)

        Timber.plant(DebugTree())

        val serviceChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "VACA Foreground Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val recoveryChannel = NotificationChannel(
            RECOVERY_CHANNEL_ID,
            "VACA Foreground Recovery Channel",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Brings View Assist Companion back to the foreground after an OS restart"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(serviceChannel)
        notificationManager.createNotificationChannel(recoveryChannel)
    }
}
