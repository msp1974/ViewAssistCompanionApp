package com.msp1974.vacompanion.sensors

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

    val sensorListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                updateSensorData("light", event.values[0].toFloat(), 10f)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

        }

        fun updateSensorData(name: String, value: Float, changeRequired: Float) {
            var lastValue = sensorLastValue.getOrDefault(name,0f) as Float
            if (abs(value - lastValue) >= changeRequired) {
                sensorData.put(name, value)
                sensorLastValue.put(name, value)
            }
        }
    }

    init {
        log.d("Starting sensors")

        // Register light sensor listener
        hasSensors = registerLightSensorListener()
        startIntervalTimer()
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

    fun getAvailableSensors(): List<Sensor> {
        val deviceSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        for (i in 0..deviceSensors.size - 1) {
            log.i("Sensor: ${deviceSensors[i]}")
        }
        return deviceSensors
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
}

