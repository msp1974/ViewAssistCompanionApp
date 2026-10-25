package com.msp1974.vacompanion.ui.layouts.config

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel

private const val DEVICE = "Device"
private const val HARDWARE = "Hardware Capabilities"
private const val SENSORS = "Sensors"

// What the device is and what it can do, so every item is read-only.
fun deviceConfig(vm: VAViewModel): ConfigSection {
    val deviceInfo = vm.deviceInfo

    return ConfigSection(
        "Device", Icons.Default.PhoneAndroid, listOf(
            ConfigItem.Info(DEVICE, "Manufacturer", null, Icons.Default.Business,
                value = { Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" } }),
            ConfigItem.Info(DEVICE, "Model", null, Icons.Default.Smartphone,
                value = { Build.MODEL.orEmpty().ifBlank { "Unknown" } }),
            ConfigItem.Info(DEVICE, "Device", null, Icons.Default.DeveloperBoard,
                value = { Build.DEVICE.orEmpty().ifBlank { "Unknown" } }),

            ConfigItem.Info(HARDWARE, "Battery", null, Icons.Default.BatteryFull,
                value = { yesNo(deviceInfo.hardware.hasBattery) },
                valueColor = { yesNoColor(deviceInfo.hardware.hasBattery) }),
            ConfigItem.Info(HARDWARE, "Front Camera", null, Icons.Default.CameraAlt,
                value = { yesNo(deviceInfo.hardware.hasFrontCamera) },
                valueColor = { yesNoColor(deviceInfo.hardware.hasFrontCamera) }),
            ConfigItem.Info(HARDWARE, "DND Support", null, Icons.Default.SettingsSuggest,
                value = { yesNo(deviceInfo.features.supportsDND) },
                valueColor = { yesNoColor(deviceInfo.features.supportsDND) }),

            ConfigItem.Info(SENSORS, "Accelerometer", null, Icons.Default.Sensors,
                value = { yesNo(deviceInfo.hardware.hasAccelerometer) },
                valueColor = { yesNoColor(deviceInfo.hardware.hasAccelerometer) }),
            ConfigItem.Info(SENSORS, "Light Sensor", null, Icons.Default.LightMode,
                value = { yesNo(deviceInfo.hardware.hasLightSensor) },
                valueColor = { yesNoColor(deviceInfo.hardware.hasLightSensor) }),
            ConfigItem.Info(SENSORS, "Proximity Sensor", null, Icons.Default.Radar,
                value = { yesNo(deviceInfo.hardware.hasProximitySensor) },
                valueColor = { yesNoColor(deviceInfo.hardware.hasProximitySensor) }),
            ConfigItem.Info(SENSORS, "Proximity Type", null, Icons.AutoMirrored.Filled.Label,
                value = {
                    if (deviceInfo.hardware.hasProximitySensor)
                        deviceInfo.hardware.proximitySensorType.replaceFirstChar { it.uppercase() }
                    else "Not available"
                }),
        )
    )
}
