package com.msp1974.vacompanion.utils

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.crashlytics
import timber.log.Timber

class Logger {
    companion object {
        const val TAG = "ViewAssistCA"
    }
    fun d(message: String) {
        Timber.tag(TAG).d(message)
    }
    fun e(message: String) {
        Timber.tag(TAG).e(message)
    }
    fun i(message: String) {
        Timber.tag(TAG).i(message)
    }
    fun w(message: String) {
        Timber.tag(TAG).w(message)
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
            Timber.tag(Logger.TAG).w("Firebase context unavailable. Firebase features disabled.")
            return
        }

        try {
            val appContext = context.applicationContext
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                Timber.tag(Logger.TAG).w("FirebaseApp not initialized. Skipping Firebase setup.")
                return
            }

            firebaseAnalytics = Firebase.analytics
            firebaseCrashlytics = Firebase.crashlytics
        } catch (e: Exception) {
            Timber.tag(Logger.TAG)
                .w(e, "Failed to initialize Firebase. Firebase features disabled.")
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
                        Timber.tag(Logger.TAG).w(e, "FirebaseManager fallback initialization used.")
                        FirebaseManager().also { instance = it }
                    }
                }
            }
        }

        const val DIAGNOSTIC_POPUP_SHOWN = "diagnostic_popup_shown"
        const val WAKE_WORD_DETECTED = "wake_word_detected"
        const val WAKE_WORD_ENGINE_STOP_TIMEOUT = "wake_word_engine_stop_timeout"
        const val WAKE_WORD_ENGINE_RESTART = "wake_word_engine_restart"
        const val WAKE_WORD_ENGINE_RESTART_GIVEN_UP = "wake_word_engine_restart_given_up"
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
