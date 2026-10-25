package com.msp1974.vacompanion.ui.layouts.config

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.utils.Helpers


private const val BEHAVIOUR = "Behaviour"
private const val DEVICE = "Device"
private const val NETWORK = "Network"

fun generalConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    val deviceInfo = vm.deviceInfo
    val context = vm.getApplication<Application>()
    val state = { vm.vacaState.value }
    return ConfigSection(
        "General", Icons.Default.Tune, listOf(
            ConfigItem.Info(DEVICE, "UUID", "Identifies this device to Home Assistant", Icons.Default.Fingerprint,
                value = { c.uuid.ifBlank { "Not set" } }),
            ConfigItem.Info(DEVICE, "App Version", null, Icons.Default.Info,
                value = { deviceInfo.software.appVersion }),
            ConfigItem.Info(DEVICE, "Android Version", null, Icons.Default.Android,
                value = { deviceInfo.software.release }),
            ConfigItem.Info(DEVICE, "WebView Version", null, Icons.Default.Code,
                value = { deviceInfo.software.webViewVersion }),

            ConfigItem.Info(NETWORK, "Wi-Fi Network (SSID)", null, Icons.Default.Wifi,
                // Looked up again if the network connects or drops.
                value = { state().isNetworkConnected; wifiSsid(context) }),
            ConfigItem.Info(NETWORK, "IP Address", null, Icons.Default.Lan,
                value = { state().isNetworkConnected; Helpers.getIpv4HostAddress().ifBlank { "Not available" } }),
        )
    )
}
