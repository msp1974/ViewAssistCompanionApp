package com.msp1974.vacompanion.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import com.msp1974.vacompanion.MainActivity
import com.msp1974.vacompanion.R

class NotificationUtils(context: Context?, private val resources: Resources?) :
    ContextWrapper(context) {
    private var notificationManager: NotificationManager? = null
    private val pendingIntent: PendingIntent?
    private val notificationIntent: Intent

    init {
        ANDROID_CHANNEL_NAME = getString(R.string.service_notification_channel_name)
        notificationIntent = Intent(context, MainActivity::class.java)
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
        val pendingFlags: Int
        if (Build.VERSION.SDK_INT >= 23) {
            pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
        }
        pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, pendingFlags)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannels()
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun createChannels() {
        val description: String? = getString(R.string.service_notification_channel_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val mChannel = NotificationChannel(ANDROID_CHANNEL_ID, ANDROID_CHANNEL_NAME, importance)
        mChannel.description = description
        this.manager!!.createNotificationChannel(mChannel)
    }

    private val manager: NotificationManager?
        get() {
            if (notificationManager == null) {
                notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
            }
            return notificationManager
        }

    fun createNotification(title: String?, message: String?): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nb = getAndroidChannelNotification(title, message)
            return nb.build()
            //getManager().notify(NOTIFICATION_ID, nb.build());
        } else {
            val nb = getAndroidNotification(title, message)
            // This ensures that navigating backward from the Activity leads out of your app to the Home screen.
            val stackBuilder = TaskStackBuilder.create(getApplicationContext())
            stackBuilder.addParentStack(MainActivity::class.java)
            stackBuilder.addNextIntent(notificationIntent)
            nb.setContentIntent(pendingIntent)
            return nb.build()
            //getManager().notify(NOTIFICATION_ID, nb.build());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun getAndroidChannelNotification(title: String?, body: String?): Notification.Builder {
        val color = ContextCompat.getColor(applicationContext, R.color.colorPrimary)
        val builder: Notification.Builder =
            Notification.Builder(applicationContext, ANDROID_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setColor(color)
                .setOngoing(true)
                .setLocalOnly(true)
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setAutoCancel(false)

        builder.setContentIntent(pendingIntent)
        return builder
    }

    fun getAndroidNotification(title: String?, body: String?): NotificationCompat.Builder {
        val color = ContextCompat.getColor(applicationContext, R.color.colorPrimary)
        return NotificationCompat.Builder(applicationContext)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(false)
            .setLocalOnly(true)
            .setColor(color)
            .setAutoCancel(false)
    }

    fun clearNotification() {
        val notificationManager =
            getApplicationContext().getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
        if (notificationManager != null) {
            notificationManager.cancelAll()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1138
        const val ANDROID_CHANNEL_ID: String = "com.msp1974.vacompanion.ANDROID"
        var ANDROID_CHANNEL_NAME: String? = ""
    }
}