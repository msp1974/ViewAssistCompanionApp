package com.msp1974.vacompanion.device.info

import android.content.Context
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Helpers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject


class DeviceInfo @Inject constructor(val context: Context, val config: APPConfig) {

    @Volatile
    var hardware = DeviceHardware(context).hardwareInfo
        private set
    val software = DeviceSoftware(context).softwareInfo
    val features = DeviceFeatures(context).featuresInfo

    /**
     * Re-reads the audio input and output devices, which change as devices are plugged in or out
     * while the rest of [hardware] is fixed. True if the lists changed.
     */
    fun refreshAudioDevices(): Boolean {
        val deviceHardware = DeviceHardware(context)
        val inputs = deviceHardware.audioInputs()
        val outputs = deviceHardware.audioOutputs()
        val current = hardware
        if (inputs == current.audioInputs && outputs == current.audioOutputs) return false
        hardware = current.copy(audioInputs = inputs, audioOutputs = outputs)
        return true
    }


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
            put("has_rtsp", hardware.hasFrontCamera)
            put("rtsp_url", rtspUrl())
            put("has_dnd", features.supportsDND)
            put("proximity_sensor_type", hardware.proximitySensorType)
            putJsonObject("audio") {
                put("max_music_volume", features.audio.maxMusicVolume)
                put("max_notification_volume", features.audio.maxNotificationVolume)
                put("max_alarm_volume", features.audio.maxAlarmVolume)
                put("has_agc", features.audio.autoGainControl)
                put("has_ns", features.audio.noiseSuppression)
                put("has_aec", features.audio.acousticEchoCancellation)
                putJsonArray("inputs") {
                    hardware.audioInputs.forEach {
                        addJsonObject {
                            put("id", it.id)
                            put("type", it.type)
                            put("type_name", it.typeName)
                            put("product_name", it.productName)
                            put("address", it.address)
                        }
                    }
                }
                putJsonArray("outputs") {
                    hardware.audioOutputs.forEach {
                        addJsonObject {
                            put("id", it.id)
                            put("type", it.type)
                            put("type_name", it.typeName)
                            put("product_name", it.productName)
                            put("address", it.address)
                        }
                    }
                }
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

    private fun rtspUrl(): String {
        if (!hardware.hasFrontCamera) return ""
        val ip = Helpers.getIpv4HostAddress()
        if (ip.isEmpty()) return ""
        return "rtsp://$ip:${config.rtspPort}/camera"
    }
}
