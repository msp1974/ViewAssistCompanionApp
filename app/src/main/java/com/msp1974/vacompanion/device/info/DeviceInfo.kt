package com.msp1974.vacompanion.device.info

import android.content.Context
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject


class DeviceInfo @Inject constructor(val context: Context) {

    val hardware = DeviceHardware(context).hardwareInfo
    val software = DeviceSoftware(context).softwareInfo
    val features = DeviceFeatures(context).featuresInfo


    @OptIn(ExperimentalSerializationApi::class)
    fun getJson(): JsonObject {
    return buildJsonObject {
            put("device_signature", hardware.deviceName)
            put("app_version", software.appVersion)
            put("sdk_version", software.sdkVersion)
            put("webview_version", software.webViewVersion)
            put("release", software.release)
            put("has_battery", hardware.hasBattery)
            put("has_front_camera", hardware.hasFrontCamera)
            put("has_dnd", features.supportsDND)
            put("proximity_sensor_type", hardware.proximitySensorType)
            putJsonObject("audio") {
                put("max_music_volume", features.audio.maxMusicVolume)
                put("max_notification_volume", features.audio.maxNotificationVolume)
                put("has_agc", features.audio.autoGainControl)
                put("has_ns", features.audio.noiseSuppression)
                put("has_aec", features.audio.acousticEchoCancellation)
            }
            putJsonArray("sensors") {
                hardware.sensors.forEach {
                    addJsonObject {
                        put("id", it.id)
                        put("name", it.name)
                        put("type", it.type)
                        put("stringType", it.stringType)
                    }
                }
            }
        }
    }
}