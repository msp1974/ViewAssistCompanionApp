package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process.myPid
import android.os.Process.myUid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    fun requiredPermissions(context: Context, sensorId: String): Array<String>

    suspend fun checkPermission(context: Context, sensorId: String): Boolean {
        return requiredPermissions(context, sensorId).all {
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
        return true
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

data class SensorState(
    val light: Float? = null,
    val proximity: Float? = null,
    val temperature: Float? = null,
    val orientation: String? = null,
    val battery: BatteryState? = null,
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        light?.let { map["light"] = it }
        proximity?.let { map["proximity"] = it }
        temperature?.let { map["temperature"] = it }
        orientation?.let { map["orientation"] = it }
        battery?.let {
            if (it.hasBattery) {
                map.putAll(it.toMap())
                map["battery_level"] = it.level
                map["battery_charging"] = it.isCharging
            }
        }
        return map
    }
}
