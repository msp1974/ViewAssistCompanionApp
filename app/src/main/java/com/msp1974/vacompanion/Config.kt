package com.msp1974.vacompanion

import android.util.Log
import org.json.JSONObject
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

enum class PipelineStatus { INACTIVE, LISTENING, STREAMING }

class Config {
    var configChangeListeners = ArrayList<InterfaceConfigChangeListener>()
    var initSettings: Boolean = false
    var name: String = "VA Wyoming"
    var version: String = "0.1.4"
    var port: Int = 10700
    var haPort: Int = 8123
    var haURL: String = ""
    var wakeword_sound: String = "none"
    var wakeword_threshold: Float = 0.6f
    var uuid: String = ""
    var isMuted: Boolean = false

    var sampleRate: Int = 16000
    var audioChannels: Int = 1
    var audioWidth: Int = 2

    var micGain: Int = 50
    var duckingPercent: Int = 10

    var wakeword: String by Delegates.observable("hey_jarvis") { property, oldValue, newValue ->
        configChangeListeners.forEach { it.onConfigChange(property, oldValue, newValue) }
    }

    var notificationVolume: Float by Delegates.observable(0.5f) { property, oldValue, newValue ->
        configChangeListeners.forEach { it.onConfigChange(property, oldValue, newValue) }
    }

    var musicVolume: Float by Delegates.observable(0.8f) { property, oldValue, newValue ->
        configChangeListeners.forEach { it.onConfigChange(property, oldValue, newValue) }
    }

    var systemVolume: Float by Delegates.observable(0.5f) { property, oldValue, newValue ->
        configChangeListeners.forEach { it.onConfigChange(property, oldValue, newValue) }
    }

    var screenBrightness: Float by Delegates.observable(0.5f) { property, oldValue, newValue ->
        configChangeListeners.forEach { it.onConfigChange(property, oldValue, newValue) }
    }

    var screenState: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        configChangeListeners.forEach { it.onConfigChange(property, oldValue, newValue) }
    }

    var swipeRefresh: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        configChangeListeners.forEach { it.onConfigChange(property, oldValue, newValue) }
    }



    fun processSettings(settingString: String) {
        initSettings = true
        val settings = JSONObject(settingString)
        if (settings.has("mic_gain")) {
            micGain = settings.getInt("mic_gain")
        }
        if (settings.has("notification_volume")) {
            notificationVolume = settings.getInt("notification_volume").toFloat() / 100
        }
        if (settings.has("music_volume")) {
            musicVolume = settings.getInt("music_volume").toFloat() / 100
        }
        if (settings.has("system_volume")) {
            systemVolume = settings.getInt("system_volume").toFloat() / 100
        }
        if (settings.has("ducking_percentage")) {
            duckingPercent = settings.getInt("ducking_percentage")
        }
        if (settings.has("screen_brightness")) {
            screenBrightness = settings.getInt("screen_brightness").toFloat() / 100
        }
        if (settings.has("screen_state")) {
            screenState = settings.getBoolean("screen_state")
        }
        if (settings.has("swipe_refresh")) {
            swipeRefresh = settings.getBoolean("swipe_refresh")
        }
        if (settings.has("wake_word")) {
            if (settings["wake_word"] != wakeword) {
                wakeword = settings["wake_word"] as String
            }
        }
        if (settings.has("wake_word_sound")) {
            wakeword_sound = settings["wake_word_sound"] as String
        }
        if (settings.has("is_muted")) {
            isMuted = settings["is_muted"] as Boolean
        }
        if (settings.has("ha_port")) {
            haPort = settings["ha_port"] as Int
        }
    }

}

object Status {
    var connectionStateChangeListeners = ArrayList<InterfaceStatusChangeListener>()
    var statusChangeListeners = ArrayList<InterfaceStatusChangeListener>()

    var connectionState: String by Delegates.observable("Disconnected") { property, oldValue, newValue ->
        connectionStateChangeListeners.forEach { it.onStatusChange(property, oldValue, newValue) }
    }
    var pipelineStatus: PipelineStatus by Delegates.observable(PipelineStatus.INACTIVE) { property, oldValue, newValue ->
        statusChangeListeners.forEach { it.onStatusChange(property, oldValue, newValue) }
    }

}

class Logger {
    fun d(message: String) {
        Log.d(Global.config.name, message)
    }
    fun e(message: String) {
        Log.e(Global.config.name, message)
    }
    fun i(message: String) {
        Log.i(Global.config.name, message)
    }
    fun w(message: String) {
        Log.w(Global.config.name, message)
    }
}

interface InterfaceConfigChangeListener {
    fun onConfigChange(property: KProperty<*>, oldValue: Any, newValue: Any)
}

interface InterfaceStatusChangeListener {
    fun onStatusChange(property: KProperty<*>, oldValue: Any, newValue: Any)
}
