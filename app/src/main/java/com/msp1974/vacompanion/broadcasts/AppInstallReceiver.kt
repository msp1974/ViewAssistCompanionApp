package com.msp1974.vacompanion.broadcasts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AppInstallReceiver(
    private val onChanged: () -> Unit
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                onChanged()
            }
        }
    }
}