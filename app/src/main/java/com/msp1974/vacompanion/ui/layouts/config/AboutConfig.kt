package com.msp1974.vacompanion.ui.layouts.config

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.utils.Helpers

private const val HA = "Home Assistant"
private const val DEVICE = "Device"
private const val SOFTWARE = "Software"
private const val NETWORK = "Network"
private const val HARDWARE = "Hardware Capabilities"
private const val SENSORS = "Sensors"
private const val SERVICES = "Services"

// Device information, so every item is read-only. Values that change are read from the live UI
// state each time the row is drawn.
fun aboutConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    val deviceInfo = vm.deviceInfo
    val context = vm.getApplication<Application>()
    val state = { vm.vacaState.value }

    val serverConnected = { state().isServerConnected && state().satelliteRunning }

    return ConfigSection(
        "About", Icons.Default.Info, listOf(
            ConfigItem.Info(DEVICE, "Device", null, Icons.Default.Smartphone,
                value = { deviceInfo.hardware.deviceName }),
            ConfigItem.Info(DEVICE, "Area", "The Home Assistant area this device is in", Icons.Default.Place,
                value = { c.area.ifBlank { "Not set" } }),
            ConfigItem.Info(DEVICE, "App Version", null, Icons.Default.Info,
                value = { deviceInfo.software.appVersion }),
            ConfigItem.Info(DEVICE, "Android Version", null, Icons.Default.Android,
                value = { deviceInfo.software.release }),
            ConfigItem.Info(DEVICE, "WebView Version", null, Icons.Default.Code,
                value = { deviceInfo.software.webViewVersion }),

            ConfigItem.Info(HA, "Server address", "The address of the HA server used to load the dashboard", Icons.Default.Link,
                value = { c.homeAssistantURL.ifBlank { "Not set" } }),
            ConfigItem.Info(HA, "Dashboard", null, Icons.Default.Dashboard,
                value = { c.homeAssistantDashboard.ifBlank { "Not set" } }),
            ConfigItem.Info(HA, "Integration version", null, Icons.Default.Extension,
                value = { c.integrationVersion }),

            ConfigItem.Info(NETWORK, "Wi-Fi Network (SSID)", null, Icons.Default.Wifi,
                // Looked up again if the network connects or drops.
                value = { state().isNetworkConnected; wifiSsid(context) }),
            ConfigItem.Info(NETWORK, "IP Address", null, Icons.Default.Lan,
                value = { state().isNetworkConnected; Helpers.getIpv4HostAddress().ifBlank { "Not available" } }),
            ConfigItem.Info(NETWORK, "RTSP Server Port", null, Icons.Default.Videocam,
                value = {
                    when {
                        state().rtspServerRunning -> c.rtspPort.toString()
                        c.rtspEnabled -> "Not running"
                        else -> "Disabled"
                    }
                }),
            ConfigItem.Info(NETWORK, "Sendspin Server Port", null, Icons.Default.Speaker,
                value = {
                    when {
                        state().sendspinRunning && c.sendspinPort > 0 -> c.sendspinPort.toString()
                        c.sendspinEnabled -> "Not running"
                        else -> "Disabled"
                    }
                }),
            ConfigItem.Info(NETWORK, "Wyoming Server Port", null, Icons.Default.SwapVert,
                value = { c.serverPort.toString() }),
            ConfigItem.Info(NETWORK, "Wyoming Server", null, Icons.Default.Dns,
                value = {
                    when {
                        serverConnected() ->
                            c.homeAssistantConnectedIP.let { if (it.isBlank()) "Connected" else "Connected to $it" }
                        state().satelliteRunning -> "Waiting for Home Assistant"
                        else -> "Not connected"
                    }
                },
                valueColor = {
                    when {
                        serverConnected() -> com.msp1974.vacompanion.ui.theme.CustomColours.GREEN
                        state().satelliteRunning -> com.msp1974.vacompanion.ui.theme.CustomColours.AMBER
                        else -> com.msp1974.vacompanion.ui.theme.CustomColours.RED
                    }
                }),

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

            ConfigItem.Info(SERVICES, "RTSP Server", null, Icons.Default.Videocam,
                value = { serviceStatus(state().rtspServerRunning, c.rtspEnabled) },
                valueColor = { serviceStatusColor(state().rtspServerRunning, c.rtspEnabled) }),
            ConfigItem.Info(SERVICES, "Sendspin Player", null, Icons.Default.Speaker,
                value = { serviceStatus(state().sendspinRunning, c.sendspinEnabled) },
                valueColor = { serviceStatusColor(state().sendspinRunning, c.sendspinEnabled) }),
            ConfigItem.Info(SERVICES, "Bluetooth Proxy", null, Icons.Default.Bluetooth,
                value = {
                    if (state().bluetoothProxyRunning && c.bluetoothProxyLocationOff) "Location services off"
                    else serviceStatus(state().bluetoothProxyRunning, c.bluetoothProxyEnabled)
                },
                valueColor = {
                    if (state().bluetoothProxyRunning && c.bluetoothProxyLocationOff)
                        com.msp1974.vacompanion.ui.theme.CustomColours.RED
                    else serviceStatusColor(state().bluetoothProxyRunning, c.bluetoothProxyEnabled)
                }),
        )
    )
}
