package com.msp1974.vacompanion.sensors

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Logger
import java.util.Timer
import kotlin.concurrent.timer
import kotlin.math.abs

interface SensorUpdatesCallback {
    fun onUpdate(data: MutableMap<String, Any>)
}

class Sensors(val context: Context, val cbFunc: SensorUpdatesCallback) {
    val log = Logger()
    var sensorManager: SensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager

    var orientationSensor: String = ""
    var hasSensors = false
    var sensorData: MutableMap<String, Any> = mutableMapOf()
    var sensorLastValue: MutableMap<String, Any> = mutableMapOf()
    var timer: Timer? = null

    var hasBattery = false
    var hasLightSensor = false

    val sensorListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                updateFloatSensorData("light", event.values[0].toFloat(), 10f)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

        }
    }

    init {
        log.d("Starting sensors")
        val dm = DeviceCapabilitiesManager(context)
        hasBattery = dm.hasBattery()

        // Register light sensor listener
        hasLightSensor = dm.hasLightSensor()
        if (hasLightSensor) {
            hasSensors = registerLightSensorListener()
        }
        startIntervalTimer()
    }

    fun updateFloatSensorData(name: String, value: Float, changeRequired: Float) {
        val lastValue = sensorLastValue.getOrDefault(name,-1f) as Float
        if (abs(value - lastValue) >= changeRequired) {
            sensorData.put(name, value)
            sensorLastValue.put(name, value)
        }
    }

    fun updateStringSensorData(name: String, value: String) {
        val lastValue = sensorLastValue.getOrDefault(name,"") as String
        if (value != lastValue) {
        sensorData.put(name, value)
        sensorLastValue.put(name, value)
        }
    }

    fun updateBoolSensorData(name: String, value: Boolean) {
        val lastValue = sensorLastValue[name] as Boolean?
        if (value != lastValue) {
            sensorData.put(name, value)
            sensorLastValue.put(name, value)
        }
    }

    private fun startIntervalTimer() {
        // Reset timer if already running
        if (timer != null) {
            timer!!.cancel()
            timer = null
        }

        // Start interval timer
        timer = timer(name="sensorTimer", initialDelay = 5000, period = 5000) {
            // Set orientation as not a listener sensor
            val o = getOrientation()
            if (orientationSensor != o) {
                sensorData.put("orientation", o)
                orientationSensor = o

            }

            // Battery info
            if (hasBattery) {
                getBatteryState()
            }

            // run callback if sensor updates
            if (sensorData.isNotEmpty()) {
                cbFunc.onUpdate(data = sensorData)
                sensorData = mutableMapOf()
            }
        }
    }

    fun stop() {
        log.d("Stopping sensors")
        sensorManager.unregisterListener(sensorListener)
        if (timer != null) {
            timer!!.cancel()
            timer = null
        }
    }

    fun getOrientation(): String {
         return if (
             context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
         ) "portrait" else "landscape"
    }

    private fun registerLightSensorListener(): Boolean {
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if(lightSensor != null){
            sensorManager.registerListener(
                sensorListener,
                lightSensor,
                SensorManager.SENSOR_DELAY_NORMAL);
            log.d("Light sensor registered")
            return true
        } else {
            log.d("No light sensor found")
            return false
        }
    }

    private fun getBatteryState() {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val batteryStatusIntExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = batteryStatusIntExtra == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatusIntExtra == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1

        updateFloatSensorData("battery_level", level.toFloat(), 1f)
        updateBoolSensorData("battery_charging", isCharging)
    }
}

