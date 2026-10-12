package com.msp1974.vacompanion.device.sensors

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.msp1974.vacompanion.audio.AudioInRouter
import kotlinx.coroutines.launch

class AudioOutputSensor(private val context: Context) : Sensor {

    companion object {
        internal val basicSensor = Sensor.BasicSensor(
            "audio_output",
            type = -5, // Custom type
            name = "Audio Output Sensor",
        )
    }

    override var onUpdate: ((String, Any) -> Unit)? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var currentState = AudioOutputState()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            updateOutputs()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            updateOutputs()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    private fun updateOutputs() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val filteredDevices = devices.filter { device ->
            AudioInRouter.isUsableOutputType(device.type)
        }

        val outputNames = filteredDevices.map { device ->
            "${device.productName} (${AudioInRouter.getDeviceTypeName(device.type)})"
        }.distinct()

        val newState = AudioOutputState(
            availableOutputs = outputNames
        )

        if (newState != currentState) {
            currentState = newState
            Sensor.sensorWorkerScope.launch {
                onSensorUpdated(basicSensor.id, currentState)
            }
        }
    }

    override fun hasSensor(context: Context): Boolean = true

    override fun requiredPermissions(): Array<String> = emptyArray()

    override suspend fun getAvailableSensors(context: Context): List<Sensor.BasicSensor> {
        return listOf(basicSensor)
    }

    override suspend fun requestSensorUpdate(context: Context) {
        updateOutputs()
    }

    override fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }
}
