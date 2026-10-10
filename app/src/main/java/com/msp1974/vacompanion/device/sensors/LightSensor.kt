package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.pm.PackageManager
import android.hardware.Sensor as AndroidSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

class LightSensor(context: Context) : Sensor, SensorEventListener {

    companion object {
        private var isListenerRegistered = false
        private var listenerLastRegistered = 0L
        private const val UPDATE_PERCENTAGE = 0.05f // 25%
        private var sensorLastValue = -1f
        private const val MAX_UPDATE_INTERVAL_MS = 5000L
        private var lastUpdateTimestamp = 0L

        internal val basicSensor = Sensor.BasicSensor(
            "light",
            type = AndroidSensor.TYPE_LIGHT,
            name = "Light Sensor",
        )
    }

    private var mySensorManager: SensorManager? = context.getSystemService(SENSOR_SERVICE) as SensorManager
    override var onUpdate: ((String, Any) -> Unit)? = null

    init {
        val sensor = mySensorManager?.getDefaultSensor(AndroidSensor.TYPE_LIGHT)
        if (sensor != null && !isListenerRegistered) {
            mySensorManager?.registerListener(this, sensor, SENSOR_DELAY_NORMAL)
            isListenerRegistered = true
            listenerLastRegistered = System.currentTimeMillis()
            Timber.d("Light sensor listener registered in init")
        }
    }

    override fun hasSensor(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_LIGHT)
    }

    override fun requiredPermissions(): Array<String> {
        return emptyArray()
    }

    override suspend fun getAvailableSensors(context: Context): List<Sensor.BasicSensor> {
        return listOf(basicSensor)
    }

    override suspend fun requestSensorUpdate(context: Context) {
        // No-op: Monitoring is handled by listener registered in init
    }

    override fun onAccuracyChanged(sensor: AndroidSensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        val value = event?.values?.get(0) ?: return
        val delta = abs(value - sensorLastValue)
        val threshold = if (sensorLastValue >= 0) sensorLastValue * UPDATE_PERCENTAGE else -1f

        if (sensorLastValue < 0 || (delta > threshold && delta > 0)) {
            if (sensorLastValue >= 0 && !isUpdateIntervalElapsed()) {
                return
            }

            sensorLastValue = value
            lastUpdateTimestamp = System.currentTimeMillis()
            Sensor.sensorWorkerScope.launch {
                onSensorUpdated(basicSensor.id, value)
            }
        }
    }

    private fun isUpdateIntervalElapsed(): Boolean {
        return (System.currentTimeMillis() - lastUpdateTimestamp) >= MAX_UPDATE_INTERVAL_MS
    }

    override fun stop() {
        mySensorManager?.unregisterListener(this)
        isListenerRegistered = false
    }
}
