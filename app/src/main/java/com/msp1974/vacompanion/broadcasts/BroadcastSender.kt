package com.msp1974.vacompanion.broadcasts

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.utils.Logger
import timber.log.Timber

class BroadcastSender {
    companion object {

        internal const val WAKE_WORD_DETECTED = "WAKE_WORD_DETECTED"
        internal const val STOP_WORD_DETECTED = "STOP_WORD_DETECTED"
        internal const val SATELLITE_STARTED = "SATELLITE_STARTED"
        internal const val SATELLITE_CLIENT_UPDATED = "SATELLITE_CLIENT_UPDATED"
        internal const val SATELLITE_STOPPED = "SATELLITE_STOPPED"
        internal const val TOAST_MESSAGE = "TOAST_MESSAGE"
        internal const val WEBVIEW_CRASH = "WEBVIEW_CRASH"
        internal const val VERSION_MISMATCH = "VERSION_MISMATCH"
        internal const val RUN_UPDATE = "RUN_UPDATE"
        internal const val OPEN_PERMISSION_SCREEN = "OPEN_PERMISSION_SCREEN"
        internal const val PERMISSION_GRANTED = "PERMISSION_GRANTED"
        internal const val CLOSE_APP = "CLOSE_APP"


        fun sendBroadcast(context: Context, action: String, extra: String? = null) {
            val intent = Intent(action)
            if (extra != null) {
                intent.putExtra("extra", extra)
            }
            Timber.i("Sending broadcast: $action")
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }
}
