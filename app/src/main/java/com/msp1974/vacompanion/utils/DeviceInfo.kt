package com.msp1974.vacompanion.utils

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.os.BatteryManager
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable

@Serializable
data class DeviceCapabilitiesData(
    val deviceSignature: String,
    val appVersion: String,
    val sdkVersion: Int,
    val release: String,
    val hasBattery: Boolean,
    val hasFrontCamera: Boolean,
    val sensors: List<String>,
)


class DeviceCapabilitiesManager(val context: Context) {

    val log = Logger()

    fun getDeviceInfo(): DeviceCapabilitiesData {
        return DeviceCapabilitiesData(
            deviceSignature = Helpers.getDeviceName().toString(),
            appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.toString(),
            sdkVersion = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.toString(),
            hasBattery = hasBattery(),
            hasFrontCamera = hasFrontCamera(),
            sensors = getAvailableSensors(),
        )
    }

    fun getAvailableSensors(): List<String> {
        // Get list of available sensor types
        val sensors: MutableList<String> = mutableListOf()
        val sensorManager: SensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
        val deviceSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)

        deviceSensors.forEach { sensor: Sensor ->
            val s = buildJsonObject {
                put("id", sensor.id)
                put("name", sensor.name)
                put("type", sensor.type)
                put("maxRange", sensor.maximumRange)
                put ("resolution", sensor.resolution)
                put("stringType", sensor.stringType)
                put("reportingMode", sensor.reportingMode)
            }
            sensors.add(s.toString())
        }
        return sensors
    }

    fun hasBattery(): Boolean {
        // Some devices report having a battery when they do not, therefore check voltage too
        // present = false or voltage = 0
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val hasBattery = batteryStatus?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
        val batteryVoltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        return hasBattery == true && batteryVoltage != 0
    }

    fun hasLightSensor(): Boolean {
        val sensorManager: SensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
        val deviceSensors = sensorManager.getSensorList(Sensor.TYPE_LIGHT)
        return deviceSensors.isNotEmpty()
    }

    fun hasFrontCamera(): Boolean {
        val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameras = cameraManager.cameraIdList
        if (cameras.size > 0) {
            cameraManager.cameraIdList.map { cameraId: String ->
                val cameraInfo = cameraManager.getCameraCharacteristics(cameraId)
                if (cameraInfo.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    return true
                }
            }
        }
        return false
    }
}
