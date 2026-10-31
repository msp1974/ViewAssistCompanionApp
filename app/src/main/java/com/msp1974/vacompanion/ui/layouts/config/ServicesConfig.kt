package com.msp1974.vacompanion.ui.layouts.config

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.theme.CustomColours

private const val CAMERA = "Camera stream"
private const val CAMERA_SECURITY = "Camera stream security"
private const val SENDSPIN = "Sendspin player"
private const val BLUETOOTH = "Bluetooth"

// Running is green; enabled but not up is amber (not red, which is hard to tell from green for
// colour-blind users); disabled keeps the normal text colour.
private fun serviceColor(running: Boolean, enabled: Boolean): Color? = when {
    running -> CustomColours.GREEN
    enabled -> CustomColours.AMBER
    else -> null
}

// Optional services this device can run for Home Assistant.
fun servicesConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    val state = { vm.vacaState.value }
    return ConfigSection(
        "Services", Icons.Default.Widgets, listOf(
            ConfigItem.Toggle(CAMERA, "Camera stream enabled", "Share the camera as an RTSP stream", Icons.Default.Videocam,
                key = "rtsp_enabled", get = { c.rtspEnabled }, set = { c.rtspEnabled = it }),
            ConfigItem.Info(CAMERA, "Status", null, Icons.Default.Info,
                value = { serviceStatus(state().rtspServerRunning, c.rtspEnabled) },
                valueColor = { serviceColor(state().rtspServerRunning, c.rtspEnabled) }),
            ConfigItem.Info(CAMERA, "Port", null, Icons.Default.Numbers, value = { c.rtspPort.toString() }),
            ConfigItem.Options(CAMERA, "Resolution", null, Icons.Default.HighQuality, key = "rtsp_resolution",
                options = listOf("480p", "720p", "1080p").map { ConfigOption(it, it) },
                get = { c.rtspResolution }, set = { c.rtspResolution = it }),
            ConfigItem.Options(CAMERA, "Image rotation", "Use if the camera feed is the wrong way up",
                Icons.Default.ScreenRotation, key = "camera_rotation_offset",
                options = listOf(0, 90, 180, 270).map {
                    ConfigOption(it.toString(), if (it == 0) "Automatic" else "Automatic + $it°")
                },
                get = { c.cameraRotationOffset.toString() }, set = { c.cameraRotationOffset = it.toInt() },
                toHaValue = { it.toInt() }),
            ConfigItem.Toggle(CAMERA, "Mirror image", "Flip the camera image left to right",
                Icons.Default.Flip, key = "camera_mirror",
                get = { c.cameraMirror }, set = { c.cameraMirror = it }),
            ConfigItem.Toggle(CAMERA, "Streaming icon", "Show an icon on screen while someone is watching",
                Icons.Default.Visibility, key = "show_rtsp_streaming_indicator",
                get = { c.showRtspStreamingIndicator }, set = { c.showRtspStreamingIndicator = it }),


            ConfigItem.Toggle(SENDSPIN, "Sendspin player enabled", "Let Music Assistant play synchronised audio on this device",
                Icons.Default.Speaker, key = "sendspin_enabled",
                get = { c.sendspinEnabled }, set = { c.sendspinEnabled = it }),
            ConfigItem.Info(SENDSPIN, "Status", null, Icons.Default.Info,
                value = { serviceStatus(state().sendspinRunning, c.sendspinEnabled) },
                valueColor = { serviceColor(state().sendspinRunning, c.sendspinEnabled) }),
            ConfigItem.Info(SENDSPIN, "Server port", null, Icons.Default.Numbers,
                value = {
                    when {
                        state().sendspinRunning && c.sendspinPort > 0 -> c.sendspinPort.toString()
                        c.sendspinEnabled -> "Not running"
                        else -> "Disabled"
                    }
                }),
            ConfigItem.Info(SENDSPIN, "Preferred server", null, Icons.Default.Dns,
                value = { c.sendspinPreferredServer.ifBlank { "Automatic" } }),
            ConfigItem.Toggle(SENDSPIN, "Now playing overlay", "Show the player on screen while Sendspin audio plays",
                Icons.Default.MusicNote, key = "sendspin_overlay_enabled",
                get = { c.sendspinOverlayEnabled }, set = { c.sendspinOverlayEnabled = it }),

            ConfigItem.Toggle(BLUETOOTH, "Bluetooth proxy",
                "Let Home Assistant use this device's Bluetooth to reach BLE devices", Icons.Default.Bluetooth,
                key = "bluetooth_proxy_enabled",
                get = { c.bluetoothProxyEnabled }, set = { c.bluetoothProxyEnabled = it }),
            ConfigItem.Info(BLUETOOTH, "Status", null, Icons.Default.Info,
                value = {
                    if (state().bluetoothProxyRunning && c.bluetoothProxyLocationOff) "Location services off"
                    else serviceStatus(state().bluetoothProxyRunning, c.bluetoothProxyEnabled)
                },
                valueColor = {
                    if (state().bluetoothProxyRunning && c.bluetoothProxyLocationOff)
                        CustomColours.AMBER
                    else serviceColor(state().bluetoothProxyRunning, c.bluetoothProxyEnabled)
                }),
            ConfigItem.Action(BLUETOOTH, "Location settings",
                "The Bluetooth proxy can only scan while Android's Location setting is on",
                Icons.Default.LocationOn,
                value = { onOff(locationEnabled(vm.getApplication<Application>())) },
                valueColor = { yesNoColor(locationEnabled(vm.getApplication<Application>())) },
                onClick = {
                    val context = vm.getApplication<Application>()
                    context.startActivity(
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }),
        )
    )
}
