package com.msp1974.vacompanion.utils

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.core.os.bundleOf
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.crashlytics

class Logger {
    companion object {
        const val TAG = "ViewAssistCA"
    }
    fun d(message: String) {
        Log.d(TAG, message)
    }
    fun e(message: String) {
        Log.e(TAG, message)
    }
    fun i(message: String) {
        Log.i(TAG, message)
    }
    fun w(message: String) {
        Log.w(TAG, message)
    }
}

class FirebaseManager private constructor(context: Context? = null) {
    private var firebaseAnalytics: FirebaseAnalytics? = null
    private var firebaseCrashlytics: FirebaseCrashlytics? = null

    init {
        initialiseFirebase(context)
    }

    private fun initialiseFirebase(context: Context?) {
        if (context == null) {
            Log.w(Logger.TAG, "Firebase context unavailable. Firebase features disabled.")
            return
        }

        try {
            val appContext = context.applicationContext
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                Log.w(Logger.TAG, "FirebaseApp not initialized. Skipping Firebase setup.")
                return
            }

            firebaseAnalytics = Firebase.analytics
            firebaseCrashlytics = Firebase.crashlytics
        } catch (e: Exception) {
            Log.w(Logger.TAG, "Failed to initialize Firebase. Firebase features disabled.", e)
            firebaseAnalytics = null
            firebaseCrashlytics = null
        }
    }

    companion object {
        @Volatile
        private var instance: FirebaseManager? = null

        fun getInstance(context: Context? = null): FirebaseManager {
            instance?.let {
                if ((it.firebaseAnalytics != null || it.firebaseCrashlytics != null) || context == null) {
                    return it
                }
            }
            return synchronized(this) {
                val existing = instance
                if (existing != null) {
                    if ((existing.firebaseAnalytics != null || existing.firebaseCrashlytics != null) || context == null) {
                        existing
                    } else {
                        FirebaseManager(context).also { instance = it }
                    }
                } else {
                    try {
                        FirebaseManager(context).also { instance = it }
                    } catch (e: Exception) {
                        Log.w(Logger.TAG, "FirebaseManager fallback initialization used.", e)
                        FirebaseManager().also { instance = it }
                    }
                }
            }
        }

        const val DIAGNOSTIC_POPUP_SHOWN = "diagnostic_popup_shown"
        const val WAKE_WORD_DETECTED = "wake_word_detected"
        const val SATELLITE_ALREADY_RUNNING_MAIN = "satellite_already_running_main"
        const val RENDER_PROCESS_KILLED = "render_process_killed"
        const val RENDER_PROCESS_CRASHED = "render_process_crashed"
        const val MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING = "main_background_task_already_running"
        const val TRIM_MEMORY_UI_HIDDEN = "trim_memory_ui_hidden"
        const val TRIM_MEMORY_BACKGROUND = "trim_memory_background"
        const val LOST_NETWORK = "lost_network"

    }

    fun Map<String, Any?>.toBundle(): Bundle = bundleOf(*this.toList().toTypedArray())

    fun setCustomKeys(keys: Map<String, Any>) {
        keys.forEach {
            firebaseCrashlytics?.setCustomKey(it.key, it.value.toString())
        }
    }

    fun logEvent(event: String, params: Map<String, String>) {
        firebaseAnalytics?.logEvent(event, params.toBundle())
    }

    fun setUserProperty(key: String, value: String) {
        firebaseAnalytics?.setUserProperty(key, value)
    }

    fun addToCrashLog(message: String) {
        firebaseCrashlytics?.log(message)
    }

    fun logException(exception: Exception) {
        firebaseCrashlytics?.recordException(exception)
    }
}
