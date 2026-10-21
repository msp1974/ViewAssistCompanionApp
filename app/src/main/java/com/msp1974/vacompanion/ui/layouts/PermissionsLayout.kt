package com.msp1974.vacompanion.ui.layouts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout
import com.msp1974.vacompanion.ui.components.MenuOption
import com.msp1974.vacompanion.ui.theme.AppTheme
import com.msp1974.vacompanion.ui.theme.CustomColours

/**
 * Data class representing a permission item in the layout.
 */
data class PermissionItem(
    val title: String,
    val description: String,
    val permission: String?,
    val isGranted: Boolean,
    val isCore: Boolean,
    val onClick: () -> Unit
)

@Composable
fun PermissionsLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vaUiState by viewModel.vacaState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh permissions status when this screen is shown or resumed
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionsStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissionsStatus()
    }

    val permissionItems = mutableListOf<PermissionItem>()

    // Core Permissions
    permissionItems.add(
        PermissionItem(
            title = "Record Audio",
            description = "Required for wake word detection and voice commands",
            permission = Manifest.permission.RECORD_AUDIO,
            isGranted = vaUiState.permissions.recordAudio,
            isCore = true,
            onClick = { viewModel.togglePermission(Manifest.permission.RECORD_AUDIO) }
        )
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionItems.add(
            PermissionItem(
                title = "Post Notifications",
                description = "Required to show the foreground service notification",
                permission = Manifest.permission.POST_NOTIFICATIONS,
                isGranted = vaUiState.permissions.postNotifications,
                isCore = true,
                onClick = { viewModel.togglePermission(Manifest.permission.POST_NOTIFICATIONS) }
            )
        )
    }

    // Optional Permissions
    if (viewModel.deviceInfo.hardware.hasFrontCamera) {
        permissionItems.add(
            PermissionItem(
                title = "Camera",
                description = "Optional: Used for vision features",
                permission = Manifest.permission.CAMERA,
                isGranted = vaUiState.permissions.camera,
                isCore = false,
                onClick = { viewModel.togglePermission(Manifest.permission.CAMERA) }
            )
        )
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        permissionItems.add(
            PermissionItem(
                title = "Write External Storage",
                description = "Optional: Used for auto-updates on older Android versions",
                permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
                isGranted = vaUiState.permissions.writeExternalStorage,
                isCore = false,
                onClick = { viewModel.togglePermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            )
        )
    }

    // Bluetooth proxy permissions
    if (viewModel.config.context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionItems.add(
                PermissionItem(
                    title = "Bluetooth Scan",
                    description = "Optional: Required for the Bluetooth proxy to scan for devices",
                    permission = Manifest.permission.BLUETOOTH_SCAN,
                    isGranted = vaUiState.permissions.bluetoothScan,
                    isCore = false,
                    onClick = { viewModel.togglePermission(Manifest.permission.BLUETOOTH_SCAN) }
                )
            )
            permissionItems.add(
                PermissionItem(
                    title = "Bluetooth Connect",
                    description = "Optional: Required for the Bluetooth proxy to connect to devices",
                    permission = Manifest.permission.BLUETOOTH_CONNECT,
                    isGranted = vaUiState.permissions.bluetoothConnect,
                    isCore = false,
                    onClick = { viewModel.togglePermission(Manifest.permission.BLUETOOTH_CONNECT) }
                )
            )
        }
        permissionItems.add(
            PermissionItem(
                title = "Location",
                description = "Optional: Required by Android for the Bluetooth proxy to scan for devices",
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                isGranted = vaUiState.permissions.location,
                isCore = false,
                onClick = { viewModel.togglePermission(Manifest.permission.ACCESS_FINE_LOCATION) }
            )
        )
    }

    permissionItems.add(
        PermissionItem(
            title = "Modify System Settings",
            description = "Optional: Allows the app to control screen brightness",
            permission = "WRITE_SETTINGS",
            isGranted = vaUiState.permissions.writeSettings,
            isCore = false,
            onClick = { viewModel.requestWriteSettingsPermission() }
        )
    )

    permissionItems.add(
        PermissionItem(
            title = "Notification Policy Access",
            description = "Optional: Allows the app to manage Do Not Disturb",
            permission = "NOTIFICATION_POLICY",
            isGranted = vaUiState.permissions.notificationPolicy,
            isCore = false,
            onClick = { viewModel.requestNotificationPolicyAccess() }
        )
    )

    permissionItems.add(
        PermissionItem(
            title = "Device Admin",
            description = "Optional: Allows the app to lock/blank the screen",
            permission = "DEVICE_ADMIN",
            isGranted = vaUiState.permissions.deviceAdmin,
            isCore = false,
            onClick = { viewModel.requestDeviceAdmin() }
        )
    )

    PermissionsContent(
        permissionItems = permissionItems,
        onBack = onBack,
        modifier = modifier
    )
}

/**
 * Stateless UI component for rendering the permissions list.
 * Separated from ViewModel for easier previewing and testing.
 */
@Composable
fun PermissionsContent(
    permissionItems: List<PermissionItem>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    MenuLayout(
        title = "App Permissions",
        level = 1,
        onClose = onBack,
        modifier = modifier,
        options = permissionItems.map { item ->
            val iconColor = when {
                item.isGranted -> CustomColours.GREEN
                item.isCore -> CustomColours.RED
                else -> CustomColours.AMBER
            }
            MenuOption(
                title = item.title,
                subtitle = if (item.isGranted) "Granted" else item.description,
                icon = if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
                iconColor = iconColor,
                onClick = item.onClick
            )
        }
    )
}

@Preview(showBackground = true)
@Composable
fun PermissionsLayoutPreview() {
    AppTheme {
        PermissionsContent(
            permissionItems = listOf(
                PermissionItem(
                    title = "Record Audio",
                    description = "Required for wake word detection and voice commands",
                    permission = null,
                    isGranted = true,
                    isCore = true,
                    onClick = {}
                ),
                PermissionItem(
                    title = "Post Notifications",
                    description = "Required to show the foreground service notification",
                    permission = null,
                    isGranted = false,
                    isCore = true,
                    onClick = {}
                ),
                PermissionItem(
                    title = "Camera",
                    description = "Optional: Used for vision features",
                    permission = null,
                    isGranted = false,
                    isCore = false,
                    onClick = {}
                ),
                PermissionItem(
                    title = "Device Admin",
                    description = "Optional: Allows the app to lock/blank the screen",
                    permission = null,
                    isGranted = true,
                    isCore = false,
                    onClick = {}
                )
            ),
            onBack = {}
        )
    }
}
