package com.msp1974.vacompanion.sensors

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.msp1974.vacompanion.utils.Logger

class Sensors(val context: Context) {
    val log = Logger()
    val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager


    val orientation: String
        get() {
         return if (
             context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
         ) "portrait" else "landscape"
        }

    fun registerLightSensorListener() {
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if(lightSensor != null){
            sensorManager.registerListener(
                lightSensorListener,
                lightSensor,
                SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    val lightSensorListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                log.i("Light Sensor: ${event.values[0]}")
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

        }
    }

}