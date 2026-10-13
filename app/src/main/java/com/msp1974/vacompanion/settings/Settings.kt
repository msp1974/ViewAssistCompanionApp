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
import com.msp1974.vacompanion.data.AvailableAlarm
import com.msp1974.vacompanion.data.AvailableWakeSound
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventNotifier
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers.Companion.round
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wakeword.AvailableWakeWordsType
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject
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
    AUTH_REQUIRED,
    ERROR
}

class APPConfig @Inject constructor(val context: Context) {
    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val log = Logger()
    private val firebase = FirebaseManager.getInstance(context)
    var eventBroadcaster: EventNotifier
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        onSharedPreferenceChangedListener(prefs, key)
    }

    init {
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        eventBroadcaster = EventNotifier()
    }

    // Constant values
    val name = NAME
    //val version = getPackageInfo(context, context.packageName)?.versionName.toString()
    val serverPort = SERVER_PORT

    // Versions
    var integrationVersion: String = "0.0.0"
    var minRequiredApkVersion: String = getPackageInfo(context, context.packageName)?.versionName.toString()


    // In memory only settings
    var accessToken: String = ""
    var tokenExpiry: Long = 0


    var initSettings: Boolean = false
    var homeAssistantConnectedIP: String = ""
    var homeAssistantHTTPPort: Int = DEFAULT_HA_HTTP_PORT
    var homeAssistantURL: String = ""
    var homeAssistantDashboard: String = ""

    var sampleRate: Int = 16000
    var audioChannels: Int = 1
    var audioWidth: Int = 2

    //var connectionCount: Int = 0
    var currentActivity: String = ""
    var backgroundTaskRunning: Boolean = false
    var backgroundTaskStatus: BackgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED
    var isRunning: Boolean = false
    var cameraStreamActive: Boolean = false
    var settingsOpen: Boolean = false

    var hasRecordAudioPermission: Boolean = false
    var hasPostNotificationPermission: Boolean = false
    var hasWriteExternalStoragePermission: Boolean = false

    // Delegates.observable (unlike its siblings above) so CameraController and the foreground
    // service can react immediately to a permission grant/revoke rather than only on next bind.
    var hasCameraPermission: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var ignoreSSLErrors: Boolean = alwaysIgnoreSSLErrors

    var availableWakeWords: AvailableWakeWordsType? = null
    var availableWakeSounds: List<AvailableWakeSound> = emptyList()
    var availableAlarms: List<AvailableAlarm> = emptyList()

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

    var alarmSound: String by Delegates.observable(DEFAULT_ALARM_SOUND) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rawProximitySensorThreshold: Int by Delegates.observable(DEFAULT_RAW_PROXIMITY_THRESHOLD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordThreshold: Float by Delegates.observable(DEFAULT_WAKE_WORD_THRESHOLD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var continueConversation: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var notificationVolume: Int by Delegates.observable(DEFAULT_NOTIFICATION_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var musicVolume: Int by Delegates.observable(DEFAULT_MUSIC_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var alarmVolume: Int by Delegates.observable(DEFAULT_ALARM_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var duckingVolume: Int by Delegates.observable(DEFAULT_DUCKING_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var mediaPlayerGain: Int by Delegates.observable(0) { property, oldValue, newValue ->
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

    var recordingWakewordEnabled: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var pairedDeviceID: String by Delegates.observable(pairedDeviceId) { property, oldValue, newValue ->
        pairedDeviceId = newValue
        onValueChangedListener(property, oldValue, newValue)
    }

    var zoomLevel: Int by Delegates.observable(0) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var textSize: Int by Delegates.observable(0) { property, oldValue, newValue ->
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

    var motionDetectionMode: String by Delegates.observable("none") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue) // or motion
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

    var screenTimeout: Int by Delegates.observable(300000) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var bumpSensitivity: Float by Delegates.observable(0.1f) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenSaver: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenSaverDisableOnTouch: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOrientationMode: String by Delegates.observable("auto") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var enableQuickActions: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var customFiles: JsonElement by Delegates.observable(buildJsonObject {
        putJsonArray("microwakeword") {}
        putJsonArray("openwakeword") {}
    }) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rtspEnabled: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rtspPort: Int by Delegates.observable(DEFAULT_RTSP_PORT) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rtspResolution: String by Delegates.observable(DEFAULT_RTSP_RESOLUTION) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rtspFps: Int by Delegates.observable(DEFAULT_RTSP_FPS) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rtspBitrate: Int by Delegates.observable(DEFAULT_RTSP_BITRATE) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    // Lower = a client joining or resyncing waits less time for a keyframe, at the cost of a
    // slightly higher bitrate. Keep in sync with rtsp_keyframe_interval in processSettings.
    var rtspKeyframeIntervalSecs: Int by Delegates.observable(DEFAULT_RTSP_KEYFRAME_INTERVAL_SECS) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rtspUsername: String by Delegates.observable("") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rtspPassword: String by Delegates.observable("") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    // -1 = automatically let Android/AudioManager pick the output; otherwise an
    // AudioDeviceInfo.id (see AudioOutputRouter, which resolves this into an actual device for
    // every player to route to). Settable via the Wyoming settings push (audio_output_id) or the
    // on-device quick actions menu - both just assign this same property.
    var audioOutputId: Int by Delegates.observable(DEFAULT_AUDIO_OUTPUT_ID) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    // -1 = AudioInRouter's normal auto-selection logic (USB > Bluetooth > Wired > built-in);
    // otherwise an AudioDeviceInfo.id to force as the microphone, falling back to auto-selection
    // if that device isn't currently connected. Settable via the Wyoming settings push
    // (audio_input_id).
    var audioInputId: Int by Delegates.observable(DEFAULT_AUDIO_INPUT_ID) { property, oldValue, newValue ->
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

    /*
    var accessToken: String
        get() = this.sharedPrefs.getString("auth_token", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("auth_token", value) }

        var tokenExpiry: Long
        get() = this.sharedPrefs.getLong("token_expiry", 0)
        set(value) = this.sharedPrefs.edit { putLong("token_expiry", value) }
    */

    var refreshToken: String
        get() = this.sharedPrefs.getString("refresh_token", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("refresh_token", value) }



    private var pairedDeviceId: String
        get() = this.sharedPrefs.getString("paired_device_id", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("paired_device_id", value) }

    var alwaysIgnoreSSLErrors: Boolean
        get() = this.sharedPrefs.getBoolean("always_ignore_ssl_errors", false)
        set(value) = this.sharedPrefs.edit { putBoolean("always_ignore_ssl_errors", value) }

    var bluetoothMicEnabled: Boolean
        get() = this.sharedPrefs.getBoolean("bluetooth_mic_enabled", false)
        set(value) = this.sharedPrefs.edit { putBoolean("bluetooth_mic_enabled", value) }

    // On-device-only display preference (not HA-driven, unlike rtspEnabled etc.) - whether to
    // show the on-screen indicator while the RTSP stream has one or more connections.
    var showRtspStreamingIndicator: Boolean
        get() = this.sharedPrefs.getBoolean("show_rtsp_streaming_indicator", true)
        set(value) = this.sharedPrefs.edit { putBoolean("show_rtsp_streaming_indicator", value) }

    // Whether to run the Sendspin player (this device appears as a synchronised audio output to
    // Music Assistant / Home Assistant). On-device preference that can also be pushed by HA, so it
    // is persisted rather than only held in memory.
    var sendspinEnabled: Boolean
        get() = this.sharedPrefs.getBoolean("sendspin_enabled", false)
        set(value) = this.sharedPrefs.edit { putBoolean("sendspin_enabled", value) }

    // Extra rotation (0, 90, 180 or 270 degrees) applied to the camera image - the RTSP stream and
    // the on-device preview - on top of the automatic orientation, for a device whose camera
    // orientation is reported wrongly. On-device preference that HA can also push.
    var cameraRotationOffset: Int
        get() = this.sharedPrefs.getInt("camera_rotation_offset", 0)
        set(value) = this.sharedPrefs.edit {
            putInt("camera_rotation_offset", (((value % 360) + 360) % 360) / 90 * 90)
        }

    fun processSettings(settingString: String) {
        val settings = Json.parseToJsonElement(settingString).jsonObject

        settings["ha_port"]?.jsonPrimitive?.intOrNull?.let { homeAssistantHTTPPort = it }
        settings["ha_url"]?.jsonPrimitive?.contentOrNull?.let { homeAssistantURL = it }
        settings["ha_dashboard"]?.jsonPrimitive?.contentOrNull?.let { homeAssistantDashboard = it }
        settings["advanced_gain"]?.jsonPrimitive?.booleanOrNull?.let { useAdvancedGain = it }
        settings["wake_word_engine"]?.jsonPrimitive?.contentOrNull?.let { wakeWordEngine = it }
        settings["wake_word"]?.jsonPrimitive?.contentOrNull?.let { wakeWord = it }
        settings["wake_word_sound"]?.jsonPrimitive?.contentOrNull?.let { wakeWordSound = it }
        settings["alarm_sound"]?.jsonPrimitive?.contentOrNull?.let { alarmSound = it }
        settings["wake_word_threshold"]?.jsonPrimitive?.floatOrNull?.let { wakeWordThreshold = (it / 10).round(2) }
        settings["raw_proximity_threshold"]?.jsonPrimitive?.intOrNull?.let { rawProximitySensorThreshold = it }
        settings["notification_volume"]?.jsonPrimitive?.floatOrNull?.let { notificationVolume = it.toInt() }
        settings["music_volume"]?.jsonPrimitive?.floatOrNull?.let { musicVolume = it.toInt() }
        settings["alarm_volume"]?.jsonPrimitive?.floatOrNull?.let { alarmVolume = it.toInt() }
        settings["ducking_volume"]?.jsonPrimitive?.floatOrNull?.let { duckingVolume = it.toInt() }
        settings["mic_gain"]?.jsonPrimitive?.intOrNull?.let { micGain = it }
        settings["mute"]?.jsonPrimitive?.booleanOrNull?.let { isMuted = it }
        settings["screen_brightness"]?.jsonPrimitive?.floatOrNull?.let { screenBrightness = it / 100 }
        settings["screen_auto_brightness"]?.jsonPrimitive?.booleanOrNull?.let { screenAutoBrightness = it }
        settings["screen_always_on"]?.jsonPrimitive?.booleanOrNull?.let { screenAlwaysOn = it }
        settings["do_not_disturb"]?.jsonPrimitive?.booleanOrNull?.let { doNotDisturb = it }
        settings["dark_mode"]?.jsonPrimitive?.booleanOrNull?.let { darkMode = it }
        settings["diagnostics_enabled"]?.jsonPrimitive?.booleanOrNull?.let { diagnosticsEnabled = it }
        settings["integration_version"]?.jsonPrimitive?.contentOrNull?.let { integrationVersion = it }
        settings["min_required_apk_version"]?.jsonPrimitive?.contentOrNull?.let { minRequiredApkVersion = it }
        settings["zoom_level"]?.jsonPrimitive?.intOrNull?.let { zoomLevel = it }
        settings["text_size"]?.jsonPrimitive?.intOrNull?.let { textSize = it }
        settings["screen_on_wake_word"]?.jsonPrimitive?.booleanOrNull?.let { screenOnWakeWord = it }
        settings["screen_on_bump"]?.jsonPrimitive?.booleanOrNull?.let { screenOnBump = it }
        settings["screen_on_proximity"]?.jsonPrimitive?.booleanOrNull?.let { screenOnProximity = it }
        settings["screen_on_motion"]?.jsonPrimitive?.booleanOrNull?.let { screenOnMotion = it }
        settings["screen_on"]?.jsonPrimitive?.booleanOrNull?.let { screenOn = it }
        settings["enable_network_recovery"]?.jsonPrimitive?.booleanOrNull?.let { enableNetworkRecovery = it }
        settings["motion_detection_mode"]?.jsonPrimitive?.contentOrNull?.let {
            motionDetectionMode = it
            enableMotionDetection = it != "none"
        }
        settings["motion_detection_sensitivity"]?.jsonPrimitive?.intOrNull?.let { motionDetectionSensitivity = it }
        settings["screen_timeout"]?.jsonPrimitive?.intOrNull?.let { screenTimeout = it * 1000 }
        settings["bump_sensitivity"]?.jsonPrimitive?.floatOrNull?.let { bumpSensitivity = it / 10 }
        settings["screen_saver"]?.jsonPrimitive?.booleanOrNull?.let { screenSaver = it }
        settings["screen_saver_disable_on_touch"]?.jsonPrimitive?.booleanOrNull?.let { screenSaverDisableOnTouch = it }
        settings["screen_orientation_mode"]?.jsonPrimitive?.contentOrNull?.let { screenOrientationMode = it }
        settings["continue_conversation"]?.jsonPrimitive?.booleanOrNull?.let { continueConversation = it }
        settings["quick_actions"]?.jsonPrimitive?.booleanOrNull?.let { enableQuickActions = it }
        settings["custom_files"]?.let { customFiles = it }
        settings["rtsp_enabled"]?.jsonPrimitive?.booleanOrNull?.let { rtspEnabled = it }
        settings["rtsp_port"]?.jsonPrimitive?.intOrNull?.let { rtspPort = it }
        settings["rtsp_resolution"]?.jsonPrimitive?.contentOrNull?.let { rtspResolution = it }
        settings["rtsp_fps"]?.jsonPrimitive?.intOrNull?.let { rtspFps = it }
        settings["rtsp_bitrate"]?.jsonPrimitive?.intOrNull?.let { rtspBitrate = it }
        settings["rtsp_keyframe_interval"]?.jsonPrimitive?.intOrNull?.let { rtspKeyframeIntervalSecs = it }
        settings["rtsp_username"]?.jsonPrimitive?.contentOrNull?.let { rtspUsername = it }
        settings["rtsp_password"]?.jsonPrimitive?.contentOrNull?.let { rtspPassword = it }
        settings["audio_output_id"]?.jsonPrimitive?.intOrNull?.let { audioOutputId = it }
        settings["audio_input_id"]?.jsonPrimitive?.intOrNull?.let { audioInputId = it }
        settings["sendspin_enabled"]?.jsonPrimitive?.booleanOrNull?.let { sendspinEnabled = it }
        settings["camera_rotation_offset"]?.jsonPrimitive?.intOrNull?.let { cameraRotationOffset = it }

        initSettings = true
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
        const val DEFAULT_ALARM_SOUND = "alarm_sound"
        const val DEFAULT_WAKE_WORD_THRESHOLD = 0.6f
        const val DEFAULT_NOTIFICATION_VOLUME = 10
        const val DEFAULT_MUSIC_VOLUME = 10
        const val DEFAULT_ALARM_VOLUME = 10
        const val DEFAULT_SCREEN_BRIGHTNESS = 0.5f
        const val DEFAULT_SCREEN_AUTO_BRIGHTNESS = true
        const val DEFAULT_DUCKING_VOLUME = 2
        const val DEFAULT_MUTE = false
        const val DEFAULT_MIC_GAIN = 0
        const val DEFAULT_RTSP_PORT = 8554
        const val DEFAULT_RTSP_RESOLUTION = "720p"
        const val DEFAULT_RTSP_FPS = 15
        const val DEFAULT_RTSP_BITRATE = 2_000_000
        const val DEFAULT_RTSP_KEYFRAME_INTERVAL_SECS = 1
        const val DEFAULT_AUDIO_OUTPUT_ID = -1
        const val DEFAULT_AUDIO_INPUT_ID = -1
        const val GITHUB_API_URL = "https://api.github.com/repos/msp1974/ViewAssist_Companion_App/releases"
        const val GITHUB_RELEASES_URL = "https://github.com/msp1974/ViewAssist_Companion_App/releases"
    }
}
