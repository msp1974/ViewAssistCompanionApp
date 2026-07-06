package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor as AndroidSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import kotlinx.coroutines.launch
import timber.log.Timber

class ProximitySensor(
    private val isRaw: Boolean = false,
    private val threshold: Float = 5f
) : Sensor, SensorEventListener {

    companion object {
        private var isListenerRegistered = false
        private var listenerLastRegistered = 0L
        private var lastCalculatedProximity = -1f

        internal val basicSensor = Sensor.BasicSensor(
            "proximity",
            type = AndroidSensor.TYPE_PROXIMITY,
            name = "Proximity Sensor",
        )
    }

    private var mySensorManager: SensorManager? = null
    override var onUpdate: ((String, Any) -> Unit)? = null

    override fun hasSensor(context: Context): Boolean {
        val sm = context.getSystemService(SENSOR_SERVICE) as SensorManager
        return sm.getDefaultSensor(AndroidSensor.TYPE_PROXIMITY) != null
    }

    override fun requiredPermissions(context: Context, sensorId: String): Array<String> {
        return emptyArray()
    }

    override suspend fun getAvailableSensors(context: Context): List<Sensor.BasicSensor> {
        return listOf(basicSensor)
    }

    override suspend fun requestSensorUpdate(context: Context) {
        val now = System.currentTimeMillis()
        if (mySensorManager == null) {
            mySensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
        }

        if (isListenerRegistered && (listenerLastRegistered + Sensor.SENSOR_LISTENER_TIMEOUT < now)) {
            mySensorManager?.unregisterListener(this)
            isListenerRegistered = false
        }

        val sensor = mySensorManager?.getDefaultSensor(AndroidSensor.TYPE_PROXIMITY)
        if (sensor != null && !isListenerRegistered) {
            mySensorManager?.registerListener(this, sensor, SENSOR_DELAY_NORMAL)
            isListenerRegistered = true
            listenerLastRegistered = now
            Timber.d("Proximity sensor listener registered")
        }
    }

    override fun onAccuracyChanged(sensor: AndroidSensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        val rawValue = event?.values?.get(0) ?: return
        val value = if (isRaw) {
            if (rawValue > threshold) 0f else 1f
        } else {
            rawValue
        }

        if (value != lastCalculatedProximity) {
            lastCalculatedProximity = value
            Sensor.sensorWorkerScope.launch {
                onSensorUpdated(basicSensor.id, value)
            }
        }
    }

    override fun stop() {
        mySensorManager?.unregisterListener(this)
        isListenerRegistered = false
    }
}
