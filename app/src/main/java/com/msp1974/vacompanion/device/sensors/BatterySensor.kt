package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.launch
import timber.log.Timber

class BatterySensor(private val hasBattery: Boolean) : Sensor {

    companion object {
        internal val basicSensor = Sensor.BasicSensor(
            "battery",
            type = -1, // Not a standard Android sensor type
            name = "Battery Sensor",
        )
    }

    override var onUpdate: ((String, Any) -> Unit)? = null
    private var lastLevel = -1f
    private var lastCharging = false

    override fun hasSensor(context: Context): Boolean {
        return hasBattery
    }

    override fun requiredPermissions(context: Context, sensorId: String): Array<String> {
        return emptyArray()
    }

    override suspend fun getAvailableSensors(context: Context): List<Sensor.BasicSensor> {
        return if (hasBattery) listOf(basicSensor) else emptyList()
    }

    override suspend fun requestSensorUpdate(context: Context) {
        if (!hasBattery) return

        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        
        val level = batteryStatus?.let { intent ->
            val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            l * 100 / s.toFloat()
        } ?: 0f

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        val batteryState = BatteryState(
            hasBattery = hasBattery,
            onBattery = chargePlug == 0,
            isCharging = isCharging,
            usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB,
            acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC,
            level = level
        )

        if (level != lastLevel || isCharging != lastCharging) {
            lastLevel = level
            lastCharging = isCharging
            Sensor.sensorWorkerScope.launch {
                onSensorUpdated(basicSensor.id, batteryState)
            }
        }
    }
}
