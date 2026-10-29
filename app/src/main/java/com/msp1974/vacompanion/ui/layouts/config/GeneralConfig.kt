package com.msp1974.vacompanion.ui.layouts.config

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.utils.Helpers


private const val BEHAVIOUR = "Behaviour"
private const val INFO = "Info"
private const val NETWORK = "Network"

fun generalConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    val deviceInfo = vm.deviceInfo
    val context = vm.getApplication<Application>()
    val state = { vm.vacaState.value }
    return ConfigSection(
        "General", Icons.Default.Tune, listOf(
            ConfigItem.Info(INFO, "UUID", "Identifies this device to Home Assistant", Icons.Default.Fingerprint,
                value = { c.uuid.ifBlank { "Not set" } }),
            ConfigItem.Info(INFO, "App Version", null, Icons.Default.Info,
                value = { deviceInfo.software.appVersion }),
            ConfigItem.Info(INFO, "Android Version", null, Icons.Default.Android,
                value = { deviceInfo.software.release }),
            ConfigItem.Info(INFO, "WebView Version", null, Icons.Default.Code,
                value = { deviceInfo.software.webViewVersion }),
            ConfigItem.Action(INFO, "Default launcher app", "Lets the app launch on boot. Tap to change in Android settings",
                Icons.Default.Home,
                value = { if (isDefaultLauncher(context)) "Yes" else "No" },
                valueColor = { yesNoColor(isDefaultLauncher(context)) },
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }),

            ConfigItem.Info(NETWORK, "Wi-Fi Network (SSID)", null, Icons.Default.Wifi,
                // Looked up again if the network connects or drops.
                value = { state().isNetworkConnected; wifiSsid(context) }),
            ConfigItem.Info(NETWORK, "IP Address", null, Icons.Default.Lan,
                value = { state().isNetworkConnected; Helpers.getIpv4HostAddress().ifBlank { "Not available" } }),
            ConfigItem.Toggle(NETWORK, "Network recovery",
                "Try to reconnect Wi-Fi if it stays down. Rooted devices may be asked for root permission",
                Icons.Default.WifiFind, key = "enable_network_recovery",
                get = { c.enableNetworkRecovery }, set = { c.enableNetworkRecovery = it }),
        )
    )
}

private fun isDefaultLauncher(context: Application): Boolean {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val default = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
    return default?.activityInfo?.packageName == context.packageName
}
