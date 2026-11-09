package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.pm.PackageManager
import android.hardware.Sensor as AndroidSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

class LightSensor(context: Context) : Sensor, SensorEventListener {

    companion object {
        private var isListenerRegistered = false
        private var listenerLastRegistered = 0L
        private const val UPDATE_PERCENTAGE = 0.05f // 5%
        @Volatile private var sensorLastValue = -1f
        private const val MAX_UPDATE_INTERVAL_MS = 5000L
        @Volatile private var lastUpdateTimestamp = 0L

        // A change that arrived inside the update interval, waiting to be sent when it is up. A sensor
        // that only reports on change goes quiet once the light is steady, so without this the last
        // reading would be dropped and HA left on an old value.
        private var pendingUpdate: Job? = null

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

        // Back to about what HA already has, so a change waiting to be sent is out of date.
        if (sensorLastValue >= 0 && (delta <= threshold || delta == 0f)) {
            cancelPendingUpdate()
            return
        }

        // Only the latest value is kept, and it is sent once the update interval is up.
        pendingUpdate?.cancel()
        val wait = if (sensorLastValue >= 0) remainingIntervalMs() else 0L
        pendingUpdate = Sensor.sensorWorkerScope.launch {
            if (wait > 0) delay(wait)
            sensorLastValue = value
            lastUpdateTimestamp = System.currentTimeMillis()
            onSensorUpdated(basicSensor.id, value)
        }
    }

    private fun remainingIntervalMs(): Long {
        return MAX_UPDATE_INTERVAL_MS - (System.currentTimeMillis() - lastUpdateTimestamp)
    }

    private fun cancelPendingUpdate() {
        pendingUpdate?.cancel()
        pendingUpdate = null
    }

    override fun stop() {
        cancelPendingUpdate()
        mySensorManager?.unregisterListener(this)
        isListenerRegistered = false
    }
}
