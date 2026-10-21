package com.msp1974.vacompanion.device.info

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import com.msp1974.vacompanion.audio.AudioInRouter
import com.msp1974.vacompanion.utils.Helpers
import timber.log.Timber

data class DeviceSensor(
    val id: Int,
    val name: String,
    val type: Int,
    val maxRange: Float,
    val resolution: Float,
    val stringType: String,
    val reportingMode: Int
)

data class AudioDeviceSummary(
    val id: Int,
    val type: Int,
    val typeName: String,
    val productName: String,
    // HAL address; often tells apart devices of the same type, e.g. two built-in mics.
    val address: String,
)

data class DeviceHardwareData(
    val deviceName: String,
    val hasAccelerometer: Boolean,
    val hasBattery: Boolean,
    val hasFrontCamera: Boolean,
    val hasLightSensor: Boolean,
    val hasMicrophone: Boolean,
    val hasProximitySensor: Boolean,
    val proximitySensorType: String,
    val sensors: List<DeviceSensor>,
    val audioInputs: List<AudioDeviceSummary>,
    val audioOutputs: List<AudioDeviceSummary>,
)

class DeviceHardware(
    private val context: Context,
) {

    val hardwareInfo = DeviceHardwareData(
        deviceName = getDeviceName(),
        hasAccelerometer = hasAccelerometerSensor(),
        hasBattery = hasBattery(),
        hasFrontCamera = hasFrontCamera(),
        hasLightSensor = hasLightSensor(),
        hasMicrophone = hasMicrophone(),
        hasProximitySensor = hasProximitySensor(),
        proximitySensorType = getProximitySensorType(),
        sensors = getAvailableSensors(),
        // Same filtering MicInputSensor/AudioOutputSensor apply, so this static snapshot agrees
        // with what those live sensors report rather than also listing tuner inputs, telephony
        // uplink, internal routing buses, etc. that AudioManager reports alongside real mics/speakers.
        audioInputs = audioInputs(),
        audioOutputs = audioOutputs(),
    )

    // Read live, so a device connected after startup can be picked up (see DeviceInfo.refreshAudioDevices).
    fun audioInputs(): List<AudioDeviceSummary> {
        return getAudioDevices(AudioManager.GET_DEVICES_INPUTS, AudioInRouter::isUsableMicrophoneType)
    }

    fun audioOutputs() = getAudioDevices(AudioManager.GET_DEVICES_OUTPUTS, AudioInRouter::isUsableOutputType)

    @SuppressLint("HardwareIds")
    private fun getDeviceName(): String {
        return Helpers.getDeviceName() ?: "Unknown"
    }

    private fun hasAccelerometerSensor(): Boolean {
        return hasSensorType(Sensor.TYPE_ACCELEROMETER)
    }

    private fun hasBattery(): Boolean {
        // Some devices report having a battery when they do not, therefore check voltage too
        // present = false or voltage = 0
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val hasBattery = batteryStatus?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
        val batteryVoltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        return hasBattery == true && batteryVoltage != 0 && Helpers.getDeviceName().toString() != "Lenovo StarView"
    }

    private fun hasFrontCamera(): Boolean {
        // First try via CameraManager (Standard way for API 21+)
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return true
                }
            }
        } catch (e: Exception) {
            // CameraManager might fail on some restricted or non-standard devices
            Timber.e(e, "Error checking front camera via CameraManager")
        }

        // Fallback: Check via PackageManager features (more robust for some devices)
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }

    private fun hasLightSensor(): Boolean {
        return hasSensorType(Sensor.TYPE_LIGHT)
    }

    private fun hasMicrophone(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }

    private fun hasProximitySensor(): Boolean {
        return hasSensorType(Sensor.TYPE_PROXIMITY)
    }


    private fun hasSensorType(sensorType: Int): Boolean {
        val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val deviceSensors = sensorManager.getSensorList(sensorType)
        return deviceSensors.isNotEmpty()
    }

    private fun getAvailableSensors(): List<DeviceSensor> {
        // Get list of available sensor types
        val sensors: MutableList<DeviceSensor> = mutableListOf()
        val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val deviceSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)

        deviceSensors.forEach { sensor ->
            sensors.add(
                DeviceSensor(
                    id = sensor.id,
                    name = sensor.name,
                    type = sensor.type,
                    maxRange = sensor.maximumRange,
                    resolution = sensor.resolution,
                    stringType = sensor.stringType,
                    reportingMode = sensor.reportingMode
                )
            )
        }

        return sensors
    }

    private fun getAudioDevices(direction: Int, isUsable: (Int) -> Boolean): List<AudioDeviceSummary> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            audioManager.getDevices(direction)
                .filter { isUsable(it.type) }
                .map { it.toSummary() }
        } catch (e: Exception) {
            Timber.e(e, "Error listing audio devices (direction=$direction)")
            emptyList()
        }
    }

    private fun AudioDeviceInfo.toSummary() = AudioDeviceSummary(
        id = id,
        type = type,
        typeName = AudioInRouter.getDeviceTypeName(type),
        productName = productName?.toString() ?: "Unknown",
        address = address,
    )

    fun getProximitySensorType(): String {
        // Some devices have raw proximity sensors that report raw ADC values
        // (IR reflection intensity) instead of standard distance or binary values.
        // E.g. Rockchip PX30_EVB reports ~50 (ambient) to >4000 (close).
        val isPx30Evb = Build.DEVICE.equals("px30_evb", ignoreCase = true) ||
                Build.MODEL.equals("px30_evb", ignoreCase = true)

        return if (isPx30Evb) "raw" else "standard"
    }
}