package com.msp1974.vacompanion.broadcasts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.msp1974.vacompanion.MainActivity
import com.msp1974.vacompanion.settings.APPConfig

class BootUpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val config = APPConfig.getInstance(context)
            val startOnBoot = config.startOnBoot
            if (startOnBoot) {
                val activityIntent = Intent(context, MainActivity::class.java)
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(activityIntent)
            }
        }
    }
}