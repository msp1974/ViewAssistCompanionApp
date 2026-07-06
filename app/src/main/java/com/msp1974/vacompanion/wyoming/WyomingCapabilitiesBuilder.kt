package com.msp1974.vacompanion.wyoming

import com.msp1974.vacompanion.device.DeviceManager
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject

class WyomingCapabilitiesBuilder @Inject constructor(deviceManager: DeviceManager) {

    val config = deviceManager.config
    val deviceInfo = deviceManager.deviceInfo

    @OptIn(ExperimentalSerializationApi::class)
    fun buildInfo(): JsonObject {
        val baseCapabilities = deviceInfo.getJson()
        val availableWakeSounds = config.availableWakeSounds
        val availableAlarms = config.availableAlarms

        return buildJsonObject {
           baseCapabilities.forEach { (key, value) -> put(key, value) }
            putJsonArray("wake_sounds") {
                availableWakeSounds.forEach { sound ->
                    add(buildJsonObject {
                        put("id", sound.id)
                        put("name", sound.name)
                    })
                }
            }
            putJsonArray("alarms") {
                availableAlarms.forEach { alarm ->
                    add(buildJsonObject {
                        put("id", alarm.id)
                        put("name", alarm.name)
                    })
                }
            }
        }
    }
}