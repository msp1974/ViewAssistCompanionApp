package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor as AndroidSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventNotifier
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

class AccelerometerSensor(
    private val eventBroadcaster: EventNotifier,
    private val bumpSensitivity: Float = 1.0f
) : Sensor, SensorEventListener {

    companion object {
        private var isListenerRegistered = false
        private var listenerLastRegistered = 0L
        private var lastAccel = FloatArray(3)
        private var lastBump = 0L

        internal val basicSensor = Sensor.BasicSensor(
            "accelerometer",
            type = AndroidSensor.TYPE_ACCELEROMETER,
            name = "Accelerometer Sensor",
        )
    }

    private var mySensorManager: SensorManager? = null
    override var onUpdate: ((String, Any) -> Unit)? = null

    override fun hasSensor(context: Context): Boolean {
        val sm = context.getSystemService(SENSOR_SERVICE) as SensorManager
        return sm.getDefaultSensor(AndroidSensor.TYPE_ACCELEROMETER) != null
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

        val sensor = mySensorManager?.getDefaultSensor(AndroidSensor.TYPE_ACCELEROMETER)
        if (sensor != null && !isListenerRegistered) {
            mySensorManager?.registerListener(this, sensor, SENSOR_DELAY_NORMAL)
            isListenerRegistered = true
            listenerLastRegistered = now
            Timber.d("Accelerometer sensor listener registered")
        }
    }

    override fun onAccuracyChanged(sensor: AndroidSensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        val currAccel = event?.values ?: return
        val now = System.currentTimeMillis()

        if (now - lastBump > 2000) {
            val prevAccel = lastAccel.clone()
            lastAccel = currAccel.clone()
            for (i in 0..2) {
                val diff = currAccel[i] - prevAccel[i]
                if (abs(prevAccel[i]) > 0 && abs(diff) > bumpSensitivity * 2) {
                    Timber.i("Device bump detected -> $i: ${abs(diff)}")
                    lastBump = now
                    eventBroadcaster.notifyEvent(Event("deviceBump", "", ""))
                    
                    Sensor.sensorWorkerScope.launch {
                        onSensorUpdated(basicSensor.id, currAccel.clone())
                    }
                    break
                }
            }
        }
    }

    override fun stop() {
        mySensorManager?.unregisterListener(this)
        isListenerRegistered = false
    }
}
