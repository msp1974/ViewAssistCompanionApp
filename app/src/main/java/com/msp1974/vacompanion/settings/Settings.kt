package com.msp1974.vacompanion.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Build.UNKNOWN
import android.provider.Settings.Secure
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import com.google.android.gms.common.util.ClientLibraryUtils.getPackageInfo
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventNotifier
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Logger
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

enum class BackgroundTaskStatus {
    NOT_STARTED,
    STARTING,
    STARTED,
}

enum class PageLoadingStage {
    NOT_STARTED,
    STARTED,
    AUTHORISING,
    AUTHORISED,
    LOADED,
    AUTH_FAILED,
}

class APPConfig(val context: Context) {
    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val log = Logger()
    private val firebase = FirebaseManager.getInstance(context)
    var eventBroadcaster: EventNotifier
    private var prefListener: Unit

    init {
        prefListener = sharedPrefs.registerOnSharedPreferenceChangeListener { prefs, key ->
            onSharedPreferenceChangedListener(prefs, key)
        }
        eventBroadcaster = EventNotifier()
    }

    // Constant values
    val name = NAME
    val version = getPackageInfo(context, context.packageName)?.versionName.toString()
    val serverPort = SERVER_PORT

    // Versions
    var integrationVersion: String = "0.0.0"
    var minRequiredApkVersion: String = version


    // In memory only settings
    var initSettings: Boolean = false
    var homeAssistantConnectedIP: String = ""
    var homeAssistantHTTPPort: Int = DEFAULT_HA_HTTP_PORT
    var homeAssistantURL: String = ""
    var homeAssistantDashboard: String = ""

    var sampleRate: Int = 16000
    var audioChannels: Int = 1
    var audioWidth: Int = 2

    //var connectionCount: Int = 0
    var atomicConnectionCount: AtomicInteger = AtomicInteger(0)
    var currentActivity: String = ""
    var backgroundTaskRunning: Boolean = false
    var backgroundTaskStatus: BackgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED
    var isRunning: Boolean = false

    var hasRecordAudioPermission: Boolean = false
    var hasPostNotificationPermission: Boolean = false
    var hasWriteExternalStoragePermission: Boolean = false
    var hasCameraPermission: Boolean = false

    var ignoreSSLErrors: Boolean = alwaysIgnoreSSLErrors

    //In memory settings with change notification
    var useAdvancedGain: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordEngine: String by Delegates.observable("openwakeword") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWord: String by Delegates.observable(DEFAULT_WAKE_WORD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordSound: String by Delegates.observable(DEFAULT_WAKE_WORD_SOUND) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rawProximitySensorThreshold: Int by Delegates.observable(DEFAULT_RAW_PROXIMITY_THRESHOLD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordThreshold: Float by Delegates.observable(DEFAULT_WAKE_WORD_THRESHOLD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var continueConversation: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var notificationVolume: Int by Delegates.observable(DEFAULT_NOTIFICATION_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var musicVolume: Int by Delegates.observable(DEFAULT_MUSIC_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var duckingVolume: Int by Delegates.observable(DEFAULT_DUCKING_VOLUME) { property, oldValue, newValue ->
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

    var diagnosticsEnabled: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var pairedDeviceID: String by Delegates.observable(pairedDeviceId) { property, oldValue, newValue ->
        pairedDeviceId = newValue
        onValueChangedListener(property, oldValue, newValue)
    }

    var zoomLevel: Int by Delegates.observable(0) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnWakeWord: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnBump: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnProximity: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnMotion: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOn: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var enableNetworkRecovery: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var enableMotionDetection: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var motionDetectionSensitivity: Int by Delegates.observable(0) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var currentPath: String by Delegates.observable("") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var lastMotion: String by Delegates.observable("") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var lastActivity: Long by Delegates.observable(0) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenTimeout: Int by Delegates.observable(3000) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var bumpSensitivity: Float by Delegates.observable(0.1f) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenSaver: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOrientationMode: String by Delegates.observable("auto") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }





    // SharedPreferences
    var canSetScreenWritePermission: Boolean
        get() = this.sharedPrefs.getBoolean("can_set_screen_write_permission", true)
        set(value) = this.sharedPrefs.edit { putBoolean("can_set_screen_write_permission", value) }

    var canSetNotificationPolicyAccess: Boolean
        get() = this.sharedPrefs.getBoolean("can_set_notification_policy_access", true)
        set(value) = this.sharedPrefs.edit { putBoolean("can_set_notification_policy_access", value) }

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

    private var pairedDeviceId: String
        get() = this.sharedPrefs.getString("paired_device_id", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("paired_device_id", value) }

    var alwaysIgnoreSSLErrors: Boolean
        get() = this.sharedPrefs.getBoolean("always_ignore_ssl_errors", false)
        set(value) = this.sharedPrefs.edit { putBoolean("always_ignore_ssl_errors", value) }

    fun processSettings(settingString: String) {
        initSettings = true
        val settings = JSONObject(settingString)
        if (settings.has("ha_port")) {
            homeAssistantHTTPPort = settings["ha_port"] as Int
        }
        if (settings.has("ha_url")) {
            homeAssistantURL = settings["ha_url"] as String
        }
        if (settings.has("ha_dashboard")) {
            homeAssistantDashboard = settings["ha_dashboard"] as String
        }
        if (settings.has("advanced_gain")) {
            useAdvancedGain = settings["advanced_gain"] as Boolean
        }
        if (settings.has("wake_word_engine")) {
            wakeWordEngine = settings["wake_word_engine"] as String
        }
        if (settings.has("wake_word")) {
            wakeWord = settings["wake_word"] as String
        }
        if (settings.has("wake_word_sound")) {
            wakeWordSound = settings["wake_word_sound"] as String
        }
        if (settings.has("wake_word_threshold")) {
            wakeWordThreshold = settings.getInt("wake_word_threshold").toFloat() / 10
        }
        if (settings.has("raw_proximity_threshold")) {
            rawProximitySensorThreshold = settings.getInt("raw_proximity_threshold")
        }
        if (settings.has("continue_conversation")) {
            continueConversation = settings["continue_conversation"] as Boolean
        }
        if (settings.has("notification_volume")) {
            notificationVolume = settings.getInt("notification_volume")
        }
        if (settings.has("music_volume")) {
            musicVolume = settings.getInt("music_volume")
        }
        if (settings.has("ducking_volume")) {
            duckingVolume = settings.getInt("ducking_volume")
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
        if (settings.has("dark_mode")) {
            darkMode = settings.getBoolean("dark_mode")
        }
        if (settings.has("diagnostics_enabled")) {
            diagnosticsEnabled = settings.getBoolean("diagnostics_enabled")
        }
        if (settings.has("integration_version")) {
            integrationVersion = settings.getString("integration_version")
        }
        if (settings.has("min_required_apk_version")) {
            minRequiredApkVersion = settings.getString("min_required_apk_version")
        }
        if (settings.has("zoom_level")) {
            zoomLevel = settings.getInt("zoom_level")
        }
        if (settings.has("screen_on_wake_word")) {
            screenOnWakeWord = settings.getBoolean("screen_on_wake_word")
        }
        if (settings.has("screen_on_bump")) {
            screenOnBump = settings.getBoolean("screen_on_bump")
        }
        if (settings.has("screen_on_proximity")) {
            screenOnProximity = settings.getBoolean("screen_on_proximity")
        }
        if (settings.has("screen_on_motion")) {
            screenOnMotion = settings.getBoolean("screen_on_motion")
        }
        if (settings.has("screen_on")) {
            screenOn = settings.getBoolean("screen_on")
        }
        if (settings.has("enable_network_recovery")) {
            enableNetworkRecovery = settings.getBoolean("enable_network_recovery")
        }
        if (settings.has("enable_motion_detection")) {
            enableMotionDetection = settings.getBoolean("enable_motion_detection")
        }
        if (settings.has("motion_detection_sensitivity")) {
            motionDetectionSensitivity = settings.getInt("motion_detection_sensitivity")
        }
        if (settings.has("screen_timeout")) {
            screenTimeout = settings.getInt("screen_timeout") * 1000
        }
        if (settings.has("bump_sensitivity")) {
            bumpSensitivity = settings.getInt("bump_sensitivity").toFloat() / 10
        }
        if (settings.has("screen_saver")) {
            screenSaver = settings.getBoolean("screen_saver")
        }
        if (settings.has("screen_orientation_mode")) {
            screenOrientationMode = settings.getString("screen_orientation_mode")
        }


        firebase.addToCrashLog("Settings update")
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

    fun onSharedPreferenceChangedListener(prefs: SharedPreferences, key: String?) {
        log.d("SharedPreference changed: $key")
        val event = Event(key.toString(), "", "")
        firebase.addToCrashLog("${key.toString()} changed")
        eventBroadcaster.notifyEvent(event)
    }

    fun onValueChangedListener(property: KProperty<*>, oldValue: Any, newValue: Any) {
        if (oldValue != newValue) {
            val event = Event(property.name, oldValue, newValue)
            firebase.addToCrashLog("${property.name} changed from $oldValue to $newValue")
            eventBroadcaster.notifyEvent(event)
        }
    }

    companion object {
        const val NAME = "VACA"
        const val SERVER_PORT = 10800
        const val DEFAULT_HA_HTTP_PORT = 8123
        const val DEFAULT_RAW_PROXIMITY_THRESHOLD = 300
        const val DEFAULT_WAKE_WORD = "hey_jarvis"
        const val DEFAULT_WAKE_WORD_SOUND = "none"
        const val DEFAULT_WAKE_WORD_THRESHOLD = 0.6f
        const val DEFAULT_NOTIFICATION_VOLUME = 10
        const val DEFAULT_MUSIC_VOLUME = 10
        const val DEFAULT_SCREEN_BRIGHTNESS = 0.5f
        const val DEFAULT_SCREEN_AUTO_BRIGHTNESS = true
        const val DEFAULT_SWIPE_REFRESH = true
        const val DEFAULT_DUCKING_VOLUME = 2
        const val DEFAULT_MUTE = false
        const val DEFAULT_MIC_GAIN = 0
        const val GITHUB_API_URL = "https://api.github.com/repos/msp1974/ViewAssist_Companion_App/releases"

        @Volatile
        private var instance: APPConfig? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: APPConfig(context).also { instance = it }
            }
    }
}
