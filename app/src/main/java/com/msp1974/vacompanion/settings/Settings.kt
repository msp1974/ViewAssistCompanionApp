package com.msp1974.vacompanion.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Build.UNKNOWN
import android.provider.Settings.Secure
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import com.msp1974.vacompanion.utils.Logger
import org.json.JSONObject
import java.util.UUID
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

interface InterfaceConfigChangeListener {
    fun onConfigChange(property: String)
}

class APPConfig(val context: Context) {
    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private var configChangeListeners: ArrayList<Map<String, InterfaceConfigChangeListener>> = arrayListOf()
    private val log = Logger()

    init {
        sharedPrefs.registerOnSharedPreferenceChangeListener { prefs, key ->
            onSharedPreferenceChangedListener(prefs, key)
        }
    }

    // Constant values
    val name = NAME
    val version = VERSION
    val serverPort = SERVER_PORT

    // In memory only settings
    var initSettings: Boolean = false
    var homeAssistantConnectedIP: String = ""
    var homeAssistantHTTPPort: Int = DEFAULT_HA_HTTP_PORT
    var homeAssistantURL: String = ""

    var sampleRate: Int = 16000
    var audioChannels: Int = 1
    var audioWidth: Int = 2

    var connectionCount: Int = 0
    var currentActivity: String = ""
    var isRunning: Boolean = false

    var hasRecordAudioPermission: Boolean = true
    var hasPostNotificationPermission: Boolean = true

    //In memory settings with change notification
    var wakeWord: String by Delegates.observable(DEFAULT_WAKE_WORD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordSound: String by Delegates.observable(DEFAULT_WAKE_WORD_SOUND) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordThreshold: Float by Delegates.observable(DEFAULT_WAKE_WORD_THRESHOLD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var notificationVolume: Float by Delegates.observable(DEFAULT_NOTIFICATION_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var musicVolume: Float by Delegates.observable(DEFAULT_MUSIC_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var duckingVolume: Float by Delegates.observable(DEFAULT_DUCKING_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var isMuted: Boolean by Delegates.observable(DEFAULT_MUTE) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var micGain: Int by Delegates.observable(DEFAULT_MIC_GAIN) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenBrightness: Float by Delegates.observable(DEFAULT_SCREEN_BRIGHTNESS) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenAutoBrightness: Boolean by Delegates.observable(DEFAULT_SCREEN_AUTO_BRIGHTNESS) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var swipeRefresh: Boolean by Delegates.observable(DEFAULT_SWIPE_REFRESH) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenAlwaysOn: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var doNotDisturb: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var darkMode: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    // SharedPreferences
    var isFirstTime: Boolean
        get() = this.sharedPrefs.getBoolean("first_time", true)
        set(value) = this.sharedPrefs.edit { putBoolean("first_time", value) }

    var canSetScreenWritePermission: Boolean
        get() = this.sharedPrefs.getBoolean("can_set_screen_write_permission", true)
        set(value) = this.sharedPrefs.edit { putBoolean("can_set_screen_write_permission", value) }

    var startOnBoot: Boolean
        get() = this.sharedPrefs.getBoolean("startOnBoot", false)
        set(value) = this.sharedPrefs.edit { putBoolean("startOnBoot", value) }

    var uuid: String
        get() = this.sharedPrefs.getString("uuid", getUUID()) ?: ""
        set(value) = this.sharedPrefs.edit { putString("uuid", value) }

    var accessToken: String
        get() = this.sharedPrefs.getString("auth_token", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("auth_token", value) }

    var refreshToken: String
        get() = this.sharedPrefs.getString("refresh_token", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("refresh_token", value) }

    var tokenExpiry: Long
        get() = this.sharedPrefs.getLong("token_expiry", 0)
        set(value) = this.sharedPrefs.edit { putLong("token_expiry", value) }

    var pairedDeviceID: String
        get() = this.sharedPrefs.getString("paired_device_id", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("paired_device_id", value) }

    fun processSettings(settingString: String) {
        initSettings = true
        val settings = JSONObject(settingString)
        if (settings.has("ha_port")) {
            homeAssistantHTTPPort = settings["ha_port"] as Int
        }
        if (settings.has("ha_url")) {
            homeAssistantURL = settings["ha_url"] as String
        }
        if (settings.has("wake_word")) {
            wakeWord = settings["wake_word"] as String
        }
        if (settings.has("wake_word_sound")) {
            wakeWordSound = settings["wake_word_sound"] as String
        }
        if (settings.has("wake_word_threshold")) {
            wakeWordThreshold = settings.getInt("wake_word_threshold").toFloat() / 100
        }
        if (settings.has("notification_volume")) {
            notificationVolume = settings.getInt("notification_volume").toFloat() / 100
        }
        if (settings.has("music_volume")) {
            musicVolume = settings.getInt("music_volume").toFloat() / 100
        }
        if (settings.has("ducking_volume")) {
            duckingVolume = settings.getInt("ducking_volume").toFloat() / 100
        }
        if (settings.has("mic_gain")) {
            micGain = settings.getInt("mic_gain")
        }
        if (settings.has("mute")) {
            isMuted = settings["mute"] as Boolean
        }
        if (settings.has("screen_brightness")) {
            screenBrightness = settings.getInt("screen_brightness").toFloat() / 100
        }
        if (settings.has("screen_auto_brightness")) {
            screenAutoBrightness = settings.getBoolean("screen_auto_brightness")
        }
        if (settings.has("swipe_refresh")) {
            swipeRefresh = settings.getBoolean("swipe_refresh")
        }
        if (settings.has("screen_always_on")) {
            screenAlwaysOn = settings.getBoolean("screen_always_on")
        }
        if (settings.has("do_not_disturb")) {
            doNotDisturb = settings.getBoolean("do_not_disturb")
        }
        if (settings.has("wake_word_threshold")) {
            wakeWordThreshold = settings.getInt("wake_word_threshold").toFloat() / 10
        }
        if (settings.has("dark_mode")) {
            darkMode = settings.getBoolean("dark_mode")
        }
    }

    @SuppressLint("HardwareIds")
    private fun getUUID(): String {
        if (Build.SERIAL != UNKNOWN) {
            if (Build.MANUFACTURER.lowercase() != "google") {
                return "${Build.MANUFACTURER}-${Build.SERIAL}".lowercase()
            } else {
                return "${Build.SERIAL}".lowercase()
            }
        }
        val aId = Secure.getString(context.applicationContext.contentResolver, Secure.ANDROID_ID)
        if (aId != null) {
            return aId.slice(0..8)
        }
        val uid = UUID.randomUUID().toString()
        return uid.slice(0..8)

    }

    fun addChangeListener(key: String, listener: InterfaceConfigChangeListener) {
        val map : Map<String, InterfaceConfigChangeListener> = mapOf(key to listener)
        configChangeListeners.add(map)
    }

    fun removeChangeListener(key: String, listener: InterfaceConfigChangeListener) {
        val map : Map<String, InterfaceConfigChangeListener> = mapOf(key to listener)
        configChangeListeners.remove(map)
    }

    fun onSharedPreferenceChangedListener(prefs: SharedPreferences, key: String?) {
        configChangeListeners.forEach {
            for (entry in it.entries) {
                if (entry.key == key) {
                    entry.value.onConfigChange(
                        key.toString(),
                    )
                }
            }
        }
    }

    fun onValueChangedListener(property: KProperty<*>, oldValue: Any, newValue: Any) {
        if (oldValue != newValue) {
            configChangeListeners.forEach {
                for (entry in it.entries) {
                    if (entry.key == property.name) {
                        entry.value.onConfigChange(entry.key.toString())
                    }
                }
            }
        }
    }

    companion object {
        const val NAME = "VACA"
        const val VERSION = "0.3.3-rc1"
        const val SERVER_PORT = 10800
        const val DEFAULT_HA_HTTP_PORT = 8123
        const val DEFAULT_WAKE_WORD = "hey_jarvis"
        const val DEFAULT_WAKE_WORD_SOUND = "none"
        const val DEFAULT_WAKE_WORD_THRESHOLD = 0.6f
        const val DEFAULT_NOTIFICATION_VOLUME = 0.5f
        const val DEFAULT_MUSIC_VOLUME = 0.8f
        const val DEFAULT_SCREEN_BRIGHTNESS = 0.5f
        const val DEFAULT_SCREEN_AUTO_BRIGHTNESS = true
        const val DEFAULT_SWIPE_REFRESH = true
        const val DEFAULT_DUCKING_VOLUME = 0.1f
        const val DEFAULT_MUTE = false
        const val DEFAULT_MIC_GAIN = 0

        @Volatile
        private var instance: APPConfig? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: APPConfig(context).also { instance = it }
            }
    }
}