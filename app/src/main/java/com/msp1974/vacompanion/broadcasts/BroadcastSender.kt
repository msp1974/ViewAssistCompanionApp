package com.msp1974.vacompanion.broadcasts

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.utils.Logger

class BroadcastSender {
    companion object {
        private var log = Logger()

        internal const val WAKE_WORD_DETECTED = "WAKE_WORD_DETECTED"
        internal const val SATELLITE_STARTED = "SATELLITE_STARTED"
        internal const val SATELLITE_STOPPED = "SATELLITE_STOPPED"
        internal const val TOAST_MESSAGE = "TOAST_MESSAGE"
        internal const val REFRESH = "REFRESH"


        fun sendBroadcast(context: Context, action: String, extra: String? = null) {
            val intent = Intent(action)
            if (extra != null) {
                intent.putExtra("extra", extra)
            }
            log.i("Sending broadcast: $action")
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }
}
