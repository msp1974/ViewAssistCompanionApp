package com.msp1974.vacompanion.ui.layouts.config

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.theme.CustomColours

private const val PERMISSIONS = "App permissions"

// The permissions the app uses and whether each has been granted. Tapping a row asks for the
// permission, or opens the system screen where it is changed.
fun permissionsConfig(vm: VAViewModel): ConfigSection {
    val granted = { vm.vacaState.value.permissions }
    val items = mutableListOf<ConfigItem>()

    fun add(
        required: Boolean, label: String, description: String, icon: ImageVector,
        isGranted: () -> Boolean, onClick: () -> Unit,
    ) {
        items.add(ConfigItem.Action(PERMISSIONS, label, description, icon,
            value = { if (isGranted()) "Granted" else "Not granted" },
            valueColor = {
                when {
                    isGranted() -> CustomColours.GREEN
                    required -> CustomColours.RED
                    else -> CustomColours.AMBER
                }
            },
            onClick = { onClick() }))
    }

    add(true, "Record audio", "Wake word detection and voice commands", Icons.Default.Mic,
        { granted().recordAudio }) { vm.togglePermission(Manifest.permission.RECORD_AUDIO) }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(true, "Post notifications", "Shows the foreground service notification", Icons.Default.Notifications,
            { granted().postNotifications }) { vm.togglePermission(Manifest.permission.POST_NOTIFICATIONS) }
    }

    if (vm.deviceInfo.hardware.hasFrontCamera) {
        add(false, "Camera", "Used for vision features", Icons.Default.CameraAlt,
            { granted().camera }) { vm.togglePermission(Manifest.permission.CAMERA) }
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        add(false, "Write external storage", "Used for auto-updates on older Android versions", Icons.Default.Storage,
            { granted().writeExternalStorage }) { vm.togglePermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    }

    if (vm.config.context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(false, "Bluetooth scan", "Lets the Bluetooth proxy scan for devices", Icons.Default.Bluetooth,
                { granted().bluetoothScan }) { vm.togglePermission(Manifest.permission.BLUETOOTH_SCAN) }
            add(false, "Bluetooth connect", "Lets the Bluetooth proxy connect to devices", Icons.Default.Link,
                { granted().bluetoothConnect }) { vm.togglePermission(Manifest.permission.BLUETOOTH_CONNECT) }
        }
        add(false, "Location", "Android requires this for the Bluetooth proxy to scan for devices", Icons.Default.LocationOn,
            { granted().location }) { vm.togglePermission(Manifest.permission.ACCESS_FINE_LOCATION) }
    }

    add(false, "Modify system settings", "Lets the app control screen brightness", Icons.Default.Settings,
        { granted().writeSettings }) { vm.requestWriteSettingsPermission() }
    add(false, "Notification policy access", "Lets the app manage Do Not Disturb", Icons.Default.DoNotDisturbOn,
        { granted().notificationPolicy }) { vm.requestNotificationPolicyAccess() }
    add(false, "Device admin", "Lets the app lock or blank the screen", Icons.Default.AdminPanelSettings,
        { granted().deviceAdmin }) { vm.requestDeviceAdmin() }

    return ConfigSection("Permissions", Icons.Default.Security, items)
}
