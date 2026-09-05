package com.msp1974.vacompanion.device.sensors

import android.content.Context
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
    private val pendingStateUpdates = mutableMapOf<String, Any>()
    
    private var batchJob: Job? = null

    init {
        setupSensors()
    }

    private fun setupSensors() {
        val config = deviceManager.config
        val deviceInfo = deviceManager.deviceInfo

        val sensors = mapOf<String, Sensor>(
            "light" to LightSensor(context),
            "temperature" to TemperatureSensor(context),
            "proximity" to ProximitySensor(context, deviceInfo.hardware.proximitySensorType == "raw", config.rawProximitySensorThreshold.toFloat()),
            "accelerometer" to AccelerometerSensor(context, config),
            "battery" to BatterySensor(context),
            "orientation" to OrientationSensor(context),
            "network" to NetworkSensor(context),
            "mic_input" to MicInputSensor(context),
        )

        // Start sensors
        for (sensor in sensors) {
            if (sensor.value.hasSensor(context)) {
                addSensor(sensor.value)
            }
        }
    }

    private fun addSensor(sensor: Sensor) {
        sensor.onUpdate = { id, data ->
            handleSensorUpdate(id, data)
        }
        sensors.add(sensor)

        // Some sensors (e.g. MicInputSensor) compute and push their initial state during
        // construction, which happens before onUpdate above is wired up - so that first push is
        // silently dropped. Explicitly request a fresh update now that onUpdate is set; this is a
        // no-op for sensors that rely on system callbacks/listeners for their updates instead.
        deviceManager.deviceManagerScope.launch {
            sensor.requestSensorUpdate(context)
        }
    }

    private fun handleSensorUpdate(id: String, data: Any) {
        synchronized(pendingUpdates) {
            when (data) {
                is BatteryState -> {
                    pendingUpdates.putAll(data.toMap())
                    pendingUpdates["battery_level"] = data.level
                    pendingUpdates["battery_charging"] = data.isCharging
                }
                is AccelerometerState -> {
                    pendingUpdates.putAll(data.toMap())
                }
                is MicInputState -> {
                    pendingUpdates.putAll(data.toMap())
                }
                is NetworkState -> {
                    pendingUpdates.putAll(data.toMap())
                }
                else -> {
                    pendingUpdates[id] = data
                }
            }
            pendingStateUpdates[id] = data
        }

        if (batchJob == null || batchJob?.isActive != true) {
            batchJob = deviceManager.deviceManagerScope.launch {
                delay(1.seconds)
                processBatchedUpdates()
            }
        }
    }

    suspend fun requestSensorUpdates() {
        sensors.forEach {
            it.requestSensorUpdate(context)
        }
    }

    private suspend fun processBatchedUpdates() {
        val updates = synchronized(pendingUpdates) {
            val u = if (pendingUpdates.isNotEmpty()) pendingUpdates.toMap() else null
            val s = if (pendingStateUpdates.isNotEmpty()) pendingStateUpdates.toMap() else null
            pendingUpdates.clear()
            pendingStateUpdates.clear()
            u to s
        }

        updates.second?.let { sMap ->
            _sensorState.update { currentState ->
                var nextState = currentState
                sMap.forEach { (id, data) ->
                    nextState = when (id) {
                        "light" -> nextState.copy(light = data as Float)
                        "proximity" -> nextState.copy(proximity = data as Float)
                        "temperature" -> nextState.copy(temperature = data as Float)
                        "battery" -> nextState.copy(battery = data as BatteryState)
                        "accelerometer" -> nextState.copy(accelerometer = data as AccelerometerState)
                        "orientation" -> nextState.copy(orientation = data as String)
                        "network" -> nextState.copy(network = data as NetworkState)
                        "mic_input" -> nextState.copy(micInput = data as MicInputState)
                        else -> nextState
                    }
                }
                nextState
            }
        }

        updates.first?.let {
            _sensorUpdates.emit(it)
        }
    }

    fun stop() {
        batchJob?.cancel()
        sensors.forEach { it.stop() }
    }
}
