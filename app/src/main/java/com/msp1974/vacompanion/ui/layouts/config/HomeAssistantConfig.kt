package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel

private const val HA = "Home Assistant"

// How this device is connected to Home Assistant, so every item is read-only.
fun homeAssistantConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    val state = { vm.vacaState.value }
    val serverConnected = { state().isServerConnected && state().satelliteRunning }

    return ConfigSection(
        "Home Assistant", Icons.Default.Home, listOf(
            ConfigItem.Info(HA, "Server address", "The address of the HA server used to load the dashboard", Icons.Default.Link,
                value = { c.homeAssistantURL.ifBlank { "Not set" } }),
            ConfigItem.Info(HA, "Dashboard", null, Icons.Default.Dashboard,
                value = { c.homeAssistantDashboard.ifBlank { "Not set" } }),
            ConfigItem.Info(HA, "Area", "The Home Assistant area this device is in", Icons.Default.Place,
                value = { c.area.ifBlank { "Not set" } }),
            ConfigItem.Info(HA, "Integration version", null, Icons.Default.Extension,
                value = { c.integrationVersion }),
            ConfigItem.Info(HA, "Wyoming Server Port", null, Icons.Default.SwapVert,
                value = { c.serverPort.toString() }),
            ConfigItem.Info(HA, "Wyoming Server", null, Icons.Default.Dns,
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
        )
    )
}
