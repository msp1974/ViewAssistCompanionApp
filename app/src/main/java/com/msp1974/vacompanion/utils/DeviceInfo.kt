package com.msp1974.vacompanion.utils

import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.webkit.WebView
import androidx.core.content.ContextCompat.getSystemService
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber


data class DeviceCapabilitiesData(
    val deviceSignature: String,
    val appVersion: String,
    val sdkVersion: Int,
    val webViewVersion: String,
    val release: String,
    val hasBattery: Boolean,
    val hasFrontCamera: Boolean,
    val hasDND: Boolean,
    val proximitySensorType: String,
    val sensors: List<JsonObject>,
    val audioInfo: JsonObject,
)


class DeviceCapabilitiesManager(val context: Context) {

    val log = Logger()
    private val firebase = FirebaseManager.getInstance(context)
    val config = APPConfig.getInstance(context)


    fun getDeviceInfo(): DeviceCapabilitiesData {
        return DeviceCapabilitiesData(
            deviceSignature = Helpers.getDeviceName().toString(),
            appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.toString(),
            sdkVersion = Build.VERSION.SDK_INT,
            webViewVersion = getWebViewVersion(),
            release = Build.VERSION.RELEASE.toString(),
            hasBattery = hasBattery(),
            hasFrontCamera = hasFrontCamera(),
            hasDND = hasDND(),
            proximitySensorType = getProximitySensorType(),
            sensors = getAvailableSensors(),
            audioInfo = getAudioInfo()
        )
    }

    fun getProximitySensorType(): String {
        // Some devices have raw proximity sensors that report raw ADC values 
        // (IR reflection intensity) instead of standard distance or binary values.
        // E.g. Rockchip PX30_EVB reports ~50 (ambient) to >4000 (close).
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        
        if (proximitySensor == null) {
            return "none"
        }

        val isPx30Evb = android.os.Build.DEVICE.equals("px30_evb", ignoreCase = true) ||
                        android.os.Build.MODEL.equals("px30_evb", ignoreCase = true)

        return if (isPx30Evb) "raw" else "standard"
    }

    fun getAvailableSensors(): List<JsonObject> {
        // Get list of available sensor types
        val sensors: MutableList<JsonObject> = mutableListOf()
        val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val deviceSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)

        deviceSensors.forEach { sensor: Sensor ->
            val s = buildJsonObject {
                put("id", sensor.id)
                put("name", sensor.name)
                put("type", sensor.type)
                put("maxRange", sensor.maximumRange)
                put("resolution", sensor.resolution)
                put("stringType", sensor.stringType)
                put("reportingMode", sensor.reportingMode)
            }
            sensors.add(s)
        }
        return sensors
    }

    fun getWebViewVersion(): String {
        try {
            val info = WebView.getCurrentWebViewPackage()
            return info!!.versionName!!
        } catch (e: Exception) {
            return "unknown"
        }
    }

    fun hasBattery(): Boolean {
        // Some devices report having a battery when they do not, therefore check voltage too
        // present = false or voltage = 0
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val hasBattery = batteryStatus?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
        val batteryVoltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        return hasBattery == true && batteryVoltage != 0 && Helpers.getDeviceName().toString() != "Lenovo StarView"
    }

    fun hasLightSensor(): Boolean {
        val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val deviceSensors = sensorManager.getSensorList(Sensor.TYPE_LIGHT)
        return deviceSensors.isNotEmpty()
    }

    fun hasSensorType(sensorType: Int): Boolean {
        val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val deviceSensors = sensorManager.getSensorList(sensorType)
        return deviceSensors.isNotEmpty()
    }

    fun hasFrontCamera(): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return true
                }
            }
        } catch (e: CameraAccessException) {
            // A CameraAccessException here might indicate permissions or
            // other device-specific issues preventing camera access.
            // Do not crash the app, but handle gracefully.
            return false
        } catch (e: IllegalArgumentException) {
            // This is crucial. Catches issues like "Illegal argument to HAL module"
            // if the cameraId or characteristics query is somehow malformed on a specific device.
            firebase.logException(e)
            return false
        } catch (e: Exception) {
            // Catch other unexpected exceptions
            return false
        }
        return false
    }

    fun hasDND(): Boolean {
        val notificationManager =  context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.isNotificationPolicyAccessGranted
    }

    fun getAudioInfo(): JsonObject {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return buildJsonObject {
            put("maxMusicVolume", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            put(
                "maxNotificationVolume",
                audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            )
        }
    }


    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun toJson(data: DeviceCapabilitiesData): JsonObject {
            return buildJsonObject {
                putJsonObject("capabilities") {
                    put("device_signature", data.deviceSignature)
                    put("app_version", data.appVersion)
                    put("sdk_version", data.sdkVersion)
                    put("webview_version", data.webViewVersion)
                    put("release", data.release)
                    put("has_battery", data.hasBattery)
                    put("has_front_camera", data.hasFrontCamera)
                    put("has_dnd", data.hasDND)
                    put("proximity_sensor_type", data.proximitySensorType)
                    putJsonObject("audio") {
                        put("max_music_volume", data.audioInfo.getValue("maxMusicVolume"))
                        put("max_notification_volume", data.audioInfo.getValue("maxNotificationVolume"))
                    }
                    putJsonArray("sensors") {
                        addAll(data.sensors)
                    }
                }
            }
        }

        fun isDoNotDisturbEnabled(context: Context): Boolean {
            val notificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.isNotificationPolicyAccessGranted) {
                return notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            } else {
                Timber.w("Unable to check do not disturb, notification policy access not granted")
                return false
            }
        }
    }
}
