package com.msp1974.vacompanion.broadcasts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.msp1974.vacompanion.MainActivity
import timber.log.Timber

class BootUpReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_BOOT_RECOVERY = "com.msp1974.vacompanion.extra.BOOT_RECOVERY"
        private const val PREF_LAST_BOOT_RECEIVER_MARKER = "last_boot_receiver_marker"
        private const val PREF_LAST_BOOT_RECOVERY_MARKER = "last_boot_recovery_marker"

        fun persistBootReceiverMarker(context: Context, action: String?) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            val marker =
                "ts=${System.currentTimeMillis()} action=${action ?: "unknown"} uptimeMs=${SystemClock.elapsedRealtime()}"
            prefs.edit { putString(PREF_LAST_BOOT_RECEIVER_MARKER, marker) }
        }

        fun persistBootRecoveryMarker(context: Context, marker: String) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            prefs.edit { putString(PREF_LAST_BOOT_RECOVERY_MARKER, marker) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
            ) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            Timber.d("Received boot intent: ${intent.action}")
            persistBootReceiverMarker(context, intent.action)
            val sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            val startOnBoot = sharedPreferences.getBoolean("startOnBoot", false)
            if (startOnBoot) {
                Timber.d("Starting app")
                val activityIntent = Intent(context, MainActivity::class.java)
                activityIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                activityIntent.putExtra(EXTRA_BOOT_RECOVERY, true)
                context.startActivity(activityIntent)
            }
        }
    }
}
