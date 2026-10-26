package com.msp1974.vacompanion.debug

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * Debug builds only. Sets the microphone mute and the app's Do Not Disturb from adb, the same as
 * the Mic Mute and DND quick actions:
 * `adb shell am broadcast -a com.msp1974.vacompanion.debug.SET_QUIET_MODE --ez muted true --ez dnd true`
 * Either extra can be left out. The result data reports what was applied.
 */
class DebugQuietModeReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DeviceManagerEntryPoint {
        fun deviceManager(): DeviceManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val deviceManager = EntryPointAccessors
            .fromApplication(context.applicationContext, DeviceManagerEntryPoint::class.java)
            .deviceManager()
        val config = deviceManager.config
        val result = mutableListOf<String>()

        if (intent.hasExtra("muted")) {
            val muted = intent.getBooleanExtra("muted", false)
            Timber.i("DebugQuietModeReceiver: mic muted=$muted")
            config.isMuted = muted
            // Keep the HA mute switch in step, as the quick action does.
            config.pendingHaSettings["mute"] = muted
            config.eventBroadcaster.notifyEvent(Event("sendSettingToHA", "mute", muted))
            result += "muted=$muted"
        }

        if (intent.hasExtra("dnd")) {
            val dnd = intent.getBooleanExtra("dnd", false)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.isNotificationPolicyAccessGranted) {
                Timber.i("DebugQuietModeReceiver: dnd=$dnd")
                notificationManager.setInterruptionFilter(
                    if (dnd) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                config.doNotDisturb = dnd
                deviceManager.updateDNDStatus(dnd)
                result += "dnd=$dnd"
            } else {
                Timber.w("DebugQuietModeReceiver: no notification policy access, dnd not changed")
                result += "dnd=denied"
            }
        }
        resultData = result.joinToString(" ")
    }
}
