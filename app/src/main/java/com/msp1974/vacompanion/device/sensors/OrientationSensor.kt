package com.msp1974.vacompanion.device.sensors

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.launch

class OrientationSensor(private val context: Context) : Sensor, ComponentCallbacks {

    companion object {
        internal val basicSensor = Sensor.BasicSensor(
            "orientation",
            type = -2, // Not a standard Android sensor type
            name = "Orientation Sensor",
        )
    }

    override var onUpdate: ((String, Any) -> Unit)? = null
    private var lastOrientation = getOrientationString(context.resources.configuration.orientation)

    init {
        context.registerComponentCallbacks(this)
        // Emit initial state
        Sensor.sensorWorkerScope.launch {
            onSensorUpdated(basicSensor.id, lastOrientation)
        }
    }

    override fun hasSensor(context: Context): Boolean = true

    override fun requiredPermissions(): Array<String> = emptyArray()

    override suspend fun getAvailableSensors(context: Context): List<Sensor.BasicSensor> {
        return listOf(basicSensor)
    }

    override suspend fun requestSensorUpdate(context: Context) {
        val currentOrientation = getOrientationString(context.resources.configuration.orientation)
        Sensor.sensorWorkerScope.launch {
            onSensorUpdated(basicSensor.id, currentOrientation)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val currentOrientation = getOrientationString(newConfig.orientation)
        if (currentOrientation != lastOrientation) {
            lastOrientation = currentOrientation
            Sensor.sensorWorkerScope.launch {
                onSensorUpdated(basicSensor.id, currentOrientation)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {}

    private fun getOrientationString(orientation: Int): String {
        return if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            "portrait"
        } else {
            "landscape"
        }
    }

    override fun stop() {
        context.unregisterComponentCallbacks(this)
    }
}
