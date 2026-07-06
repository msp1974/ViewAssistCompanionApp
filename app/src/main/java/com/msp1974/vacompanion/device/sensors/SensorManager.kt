package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.content.res.Configuration
import com.msp1974.vacompanion.device.DeviceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class SensorManager(
    private val context: Context,
    private val deviceManager: DeviceManager
) {

    private val sensors = mutableListOf<Sensor>()
    
    private val _sensorState = MutableStateFlow(SensorState())
    val sensorState: StateFlow<SensorState> = _sensorState.asStateFlow()

    private val _sensorUpdates = MutableSharedFlow<Map<String, Any>>(extraBufferCapacity = 10)
    val sensorUpdates: SharedFlow<Map<String, Any>> = _sensorUpdates.asSharedFlow()

    private val pendingUpdates = mutableMapOf<String, Any>()
    private var lastOrientation = ""
    
    private var intervalJob: Job? = null

    init {
        setupSensors()
        startPeriodicUpdates()
    }

    private fun setupSensors() {
        val config = deviceManager.config
        val deviceInfo = deviceManager.deviceInfo

        // Light Sensor
        val lightSensor = LightSensor()
        if (lightSensor.hasSensor(context)) {
            addSensor(lightSensor)
        }

        // Temperature Sensor
        val tempSensor = TemperatureSensor()
        if (tempSensor.hasSensor(context)) {
            addSensor(tempSensor)
        }

        // Proximity Sensor
        val isRawProximity = deviceInfo.hardware.proximitySensorType == "raw"
        val proximitySensor = ProximitySensor(
            isRaw = isRawProximity,
            threshold = config.rawProximitySensorThreshold.toFloat()
        )
        if (proximitySensor.hasSensor(context)) {
            addSensor(proximitySensor)
        }

        // Accelerometer Sensor (Bump detection)
        val accelSensor = AccelerometerSensor(
            eventBroadcaster = config.eventBroadcaster,
            bumpSensitivity = config.bumpSensitivity
        )
        if (accelSensor.hasSensor(context)) {
            addSensor(accelSensor)
        }

        // Battery Sensor
        val batterySensor = BatterySensor(hasBattery = deviceInfo.hardware.hasBattery)
        if (batterySensor.hasSensor(context)) {
            addSensor(batterySensor)
        }
    }

    private fun addSensor(sensor: Sensor) {
        sensor.onUpdate = { id, data ->
            handleSensorUpdate(id, data)
        }
        sensors.add(sensor)
    }

    private fun handleSensorUpdate(id: String, data: Any) {
        synchronized(pendingUpdates) {
            if (data is BatteryState) {
                pendingUpdates.putAll(data.toMap())
                pendingUpdates["battery_level"] = data.level
                pendingUpdates["battery_charging"] = data.isCharging
            } else {
                pendingUpdates[id] = data
            }
        }

        _sensorState.update { currentState ->
            when (id) {
                "light" -> currentState.copy(light = data as Float)
                "proximity" -> currentState.copy(proximity = data as Float)
                "temperature" -> currentState.copy(temperature = data as Float)
                "battery" -> currentState.copy(battery = data as BatteryState)
                else -> currentState
            }
        }
    }

    private fun startPeriodicUpdates() {
        intervalJob?.cancel()
        intervalJob = deviceManager.deviceManagerScope.launch {
            while (true) {
                updateSensors()
                delay(5.seconds)
            }
        }
    }

    private suspend fun updateSensors() {
        sensors.forEach { it.requestSensorUpdate(context) }
        
        // Orientation check
        val currentOrientation = getOrientation()
        if (currentOrientation != lastOrientation) {
            lastOrientation = currentOrientation
            synchronized(pendingUpdates) {
                pendingUpdates["orientation"] = currentOrientation
            }
            _sensorState.update { it.copy(orientation = currentOrientation) }
        }

        // Emit and clear pending updates
        val updates = synchronized(pendingUpdates) {
            if (pendingUpdates.isNotEmpty()) {
                val copy = pendingUpdates.toMap()
                pendingUpdates.clear()
                copy
            } else null
        }
        
        updates?.let { 
            _sensorUpdates.emit(it)
        }
    }

    private fun getOrientation(): String {
        return if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            "portrait"
        } else {
            "landscape"
        }
    }

    fun stop() {
        intervalJob?.cancel()
        sensors.forEach { it.stop() }
    }
}
