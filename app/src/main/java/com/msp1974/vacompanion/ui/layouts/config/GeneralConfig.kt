package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel


private const val BEHAVIOUR = "Behaviour"

fun generalConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    return ConfigSection(
        "General", Icons.Default.Tune, listOf(




            ConfigItem.Toggle(BEHAVIOUR, "Quick actions",
                "Swipe up from the bottom of the screen for the quick actions menu", Icons.Default.Dashboard,
                key = "quick_actions", get = { c.enableQuickActions }, set = { c.enableQuickActions = it }),
            ConfigItem.Toggle(BEHAVIOUR, "Diagnostics", "Show the diagnostics bar", Icons.Default.MonitorHeart,
                key = "diagnostics_enabled", get = { c.diagnosticsEnabled }, set = { c.diagnosticsEnabled = it }),
            ConfigItem.Toggle(BEHAVIOUR, "Network recovery",
                "Try to reconnect automatically when the network drops", Icons.Default.Wifi,
                key = "enable_network_recovery", get = { c.enableNetworkRecovery },
                set = { c.enableNetworkRecovery = it }),
            ConfigItem.Info(BEHAVIOUR, "Do not disturb", null, Icons.Default.DoNotDisturbOn,
                value = { onOff(c.doNotDisturb) }),
        )
    )
}
