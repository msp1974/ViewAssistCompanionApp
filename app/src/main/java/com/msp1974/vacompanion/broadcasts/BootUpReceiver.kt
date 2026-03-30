package com.msp1974.vacompanion.broadcasts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import com.msp1974.vacompanion.MainActivity
import timber.log.Timber

class BootUpReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_BOOT_RECOVERY = "com.msp1974.vacompanion.extra.BOOT_RECOVERY"
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
