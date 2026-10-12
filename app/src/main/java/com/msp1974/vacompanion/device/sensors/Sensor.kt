package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process.myPid
import android.os.Process.myUid
import androidx.webkit.internal.ApiFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface Sensor {

    companion object {
        const val SENSOR_LISTENER_TIMEOUT = 60000
        val sensorWorkerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    data class BasicSensor(
        val id: String,
        val type: Int,
        val name: String,
    )

    /**
     * Get list of Android permissions that are required to use this sensor
     */
    fun requiredPermissions(): Array<String>

    suspend fun checkPermission(context: Context): Boolean {
        return requiredPermissions().all {
            context.checkPermission(it, myPid(), myUid()) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request to update a sensor, without a corresponding broadcast intent.
     */
    suspend fun requestSensorUpdate(context: Context)


    suspend fun getAvailableSensors(context: Context): List<BasicSensor>

    /**
     * Check if the user's device supports this type of sensor
     */
    fun hasSensor(context: Context): Boolean {
        return false
    }

    suspend fun onSensorUpdated(sensorId: String, data: Any) {
        onUpdate?.invoke(sensorId, data)
    }

    fun stop() {}

    var onUpdate: ((String, Any) -> Unit)?
}

data class BatteryState(
    val hasBattery: Boolean = false,
    val onBattery: Boolean = false,
    val isCharging: Boolean = false,
    val usbCharge: Boolean = false,
    val acCharge: Boolean = false,
    val level: Float = 0f
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "has_battery" to hasBattery,
            "on_battery" to onBattery,
            "is_charging" to isCharging,
            "usb_charge" to usbCharge,
            "ac_charge" to acCharge,
            "level" to level
        )
    }
}

data class AccelerometerState(
    val lastBump: Long = 0L,
    val acceleration: FloatArray = FloatArray(3)
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "accelerometer" to acceleration.toList(),
            "last_bump" to lastBump
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AccelerometerState
        if (lastBump != other.lastBump) return false
        if (!acceleration.contentEquals(other.acceleration)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = lastBump.hashCode()
        result = 31 * result + acceleration.contentHashCode()
        return result
    }
}

enum class NetworkStatus {
    Available,
    Unavailable
}

data class NetworkState(
    val status: NetworkStatus = NetworkStatus.Available,
    val type: String = "None",
    val lastChanged: Long = 0,
    val disconnectCount: Long = 0
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "network_status" to status.name,
            "network_type" to type,
            "network_last_changed" to lastChanged,
            "network_disconnect_count" to disconnectCount
        )
    }
}

data class MicInputState(
    val availableInputs: List<String> = emptyList(),
    val activeInput: String = "None"
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "available_mic_inputs" to availableInputs,
            "active_mic_input" to activeInput
        )
    }
}

data class AudioOutputState(
    val availableOutputs: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "available_audio_outputs" to availableOutputs
        )
    }
}

data class SensorState(
    val light: Float? = null,
    val proximity: Float? = null,
    val temperature: Float? = null,
    val orientation: String? = null,
    val battery: BatteryState? = null,
    val accelerometer: AccelerometerState? = null,
    val network: NetworkState? = null,
    val micInput: MicInputState? = null,
    val audioOutput: AudioOutputState? = null,
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        light?.let { map["light"] = it }
        proximity?.let { map["proximity"] = it }
        temperature?.let { map["temperature"] = it }
        orientation?.let { map["orientation"] = it }
        accelerometer?.let { map.putAll(it.toMap()) }
        battery?.let {
            if (it.hasBattery) {
                map.putAll(it.toMap())
                map["battery_level"] = it.level
                map["battery_charging"] = it.isCharging
            }
        }
        network?.let { map.putAll(it.toMap()) }
        micInput?.let { map.putAll(it.toMap()) }
        audioOutput?.let { map.putAll(it.toMap()) }
        return map
    }
}
