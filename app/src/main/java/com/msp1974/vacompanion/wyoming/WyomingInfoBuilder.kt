package com.msp1974.vacompanion.wyoming

import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.info.DeviceInfo
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class WyomingInfoBuilder(private val deviceManager: DeviceManager) {

    @OptIn(ExperimentalSerializationApi::class)
    fun buildInfo(): JsonObject {
        val availableWakeWords = deviceManager.config.availableWakeWords


        return buildJsonObject {
            put("version", deviceManager.deviceInfo.software.appVersion)
            putJsonArray("asr") {}
            putJsonArray("tts") {}
            putJsonArray("handle") {}
            putJsonArray("intent") {}
            putJsonArray("wake") {
                add(buildJsonObject {
                    put("name", "available_wake_words")
                    putJsonObject("attribution") {
                        put("name", "")
                        put("url", "")
                    }
                    put("installed", true)
                    putJsonArray("models") {
                        availableWakeWords?.forEach { (wakeWordEngineType, wakeWordWithIdList) ->
                            wakeWordWithIdList.forEach { entry ->
                                add(buildJsonObject {
                                    put("name", entry.id)
                                    putJsonObject("attribution") {
                                        put("name", wakeWordEngineType.lowercase())
                                        put("url", "")
                                    }
                                    put("installed", true)
                                    putJsonArray("languages") { add(JsonPrimitive("en")) }
                                    put("phrase", entry.wakeWord.wake_word)
                                })
                            }
                        }
                    }
                })
            }
            putJsonArray("stt") {}
            putJsonObject("satellite") {
                put("name", "VACA ${deviceManager.config.uuid}")
                putJsonObject("attribution") {
                    put("name", "")
                    put("url", "")
                }
                put("installed", true)
                put("description", "View Assist Companion App")
                put("version", deviceManager.deviceInfo.software.appVersion)
                put("area", "")
                put("has_vad", false)
                putJsonObject("snd_format") {
                    put("channels", 1)
                    put("rate", 16000)
                    put("width", 2)
                }
                putJsonArray("active_wake_words") { add(JsonPrimitive(deviceManager.config.wakeWord)) }
                put("max_active_wake_words", 1)
            }
        }
    }
}
