package com.msp1974.vacompanion.ui.layouts.config

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel

private const val SENDSPIN = "Sendspin player"
private const val BLUETOOTH = "Bluetooth"

fun mediaConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    return ConfigSection(
        "Media & Bluetooth", Icons.Default.Bluetooth, listOf(
            ConfigItem.Toggle(SENDSPIN, "Sendspin player", "Let Music Assistant play synchronised audio on this device",
                Icons.Default.Speaker, key = "sendspin_enabled",
                get = { c.sendspinEnabled }, set = { c.sendspinEnabled = it }),
            ConfigItem.Info(SENDSPIN, "Preferred server", null, Icons.Default.Dns,
                value = { c.sendspinPreferredServer.ifBlank { "Automatic" } }),
            ConfigItem.Toggle(SENDSPIN, "Now playing overlay", "Show the player on screen while Sendspin audio plays",
                Icons.Default.MusicNote, key = "sendspin_overlay_enabled",
                get = { c.sendspinOverlayEnabled }, set = { c.sendspinOverlayEnabled = it }),

            ConfigItem.Toggle(BLUETOOTH, "Bluetooth proxy",
                "Let Home Assistant use this device's Bluetooth to reach BLE devices", Icons.Default.Bluetooth,
                key = "bluetooth_proxy_enabled",
                get = { c.bluetoothProxyEnabled }, set = { c.bluetoothProxyEnabled = it }),
            ConfigItem.Action(BLUETOOTH, "Location settings",
                "The Bluetooth proxy can only scan while Android's Location setting is on",
                Icons.Default.LocationOn,
                onClick = {
                    val context = vm.getApplication<Application>()
                    context.startActivity(
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }),
            ConfigItem.Toggle(BLUETOOTH, "Bluetooth microphone", null, Icons.Default.Mic,
                key = "bluetooth_mic_enabled",
                get = { c.bluetoothMicEnabled }, set = { c.bluetoothMicEnabled = it }),
        )
    )
}
