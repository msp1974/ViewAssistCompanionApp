package com.msp1974.vacompanion.ui.layouts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DisabledByDefault
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.zIndex
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout
import com.msp1974.vacompanion.ui.components.SettingsTile
import com.msp1974.vacompanion.ui.components.SettingsTileGrid
import com.msp1974.vacompanion.ui.components.UUIDEditDialog
import com.msp1974.vacompanion.ui.components.VADialog

/**
 * Screen identifiers for the settings navigation.
 */
enum class SettingsScreen {
    MAIN,
    CUSTOM_FILES,
    CAMERA_STREAM,
    CONFIGURATION,
    APP_LAUNCHER
}

enum class Dialog {
    NONE,
    CLEAR_PAIRING,
    EDIT_UUID
}

/**
 * A container for settings that supports multiple screens with navigation.
 */
@Composable
fun SettingsLayout(
    viewModel: VAViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vaUiState by viewModel.vacaState.collectAsState()
    var currentScreen by remember { mutableStateOf(SettingsScreen.MAIN) }
    var currentDialog by remember {mutableStateOf(Dialog.NONE)}

    Surface(
        modifier = modifier
            .fillMaxSize()
            .zIndex(10f),
        color = Color.White // As requested, white background for menu
    ) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                } else {
                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                }
            },
            label = "SettingsNavigation"
        ) { screen ->
            when (screen) {
                SettingsScreen.MAIN -> {
                    val tiles = mutableListOf<SettingsTile>()
                    // Colours come from the same palette as the Configuration sections, in order.
                    fun add(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
                        tiles.add(SettingsTile(title, subtitle, icon, sectionColor(tiles.size), onClick))
                    }

                    add("Configuration", "View the settings provided by Home Assistant", Icons.Default.Tune) {
                        currentScreen = SettingsScreen.CONFIGURATION
                    }
                    add("Manage Custom Files", "Manage custom wake words, sounds and alarms", Icons.Default.FileCopy) {
                        currentScreen = SettingsScreen.CUSTOM_FILES
                    }
                    add("App Launcher", "Launch other apps from a page of tiles you choose", Icons.Default.Apps) {
                        currentScreen = SettingsScreen.APP_LAUNCHER
                    }
                    if (vaUiState.menuOpenedByAction && viewModel.deviceInfo.hardware.hasFrontCamera) {
                        add("View Camera Stream", "Check the camera feed for motion or face detection", Icons.Default.Videocam) {
                            currentScreen = SettingsScreen.CAMERA_STREAM
                        }
                    }
                    if (!vaUiState.menuOpenedByAction) {
                        add("Edit Device ID", "Change the unique ID for this device", Icons.Default.Fingerprint) {
                            currentDialog = Dialog.EDIT_UUID
                        }
                    }
                    add("Unpair Device", "Unpair from the Home Assistant server and reset authentication", Icons.Default.DisabledByDefault) {
                        currentDialog = Dialog.CLEAR_PAIRING
                    }

                    MenuLayout(
                        title = "Menu",
                        onClose = onClose
                    ) {
                        SettingsTileGrid(tiles)
                    }
                }
                SettingsScreen.CUSTOM_FILES -> {
                    CustomFilesLayout(
                        viewModel = viewModel,
                        onBack = { currentScreen = SettingsScreen.MAIN }
                    )
                }
                SettingsScreen.CAMERA_STREAM -> {
                    CameraStreamLayout(
                        viewModel = viewModel,
                        onBack = { currentScreen = SettingsScreen.MAIN }
                    )
                }
                SettingsScreen.APP_LAUNCHER -> {
                    AppLauncherLayout(
                        viewModel = viewModel,
                        onBack = { currentScreen = SettingsScreen.MAIN }
                    )
                }
                SettingsScreen.CONFIGURATION -> {
                    ConfigurationLayout(
                        viewModel = viewModel,
                        onBack = { currentScreen = SettingsScreen.MAIN }
                    )
                }
            }
            when (currentDialog) {
                Dialog.NONE -> {}
                Dialog.EDIT_UUID -> {
                    UUIDEditDialog(
                        onDismissRequest = {},
                        onConfirmation = viewModel::setUUID,
                        onClose = { currentDialog = Dialog.NONE },
                        initText = viewModel.config.uuid
                    )
                }
                Dialog.CLEAR_PAIRING -> {
                    VADialog(
                        onDismissRequest = { currentDialog = Dialog.NONE },
                        onConfirmation = {
                            viewModel.unpairDevice()
                            currentDialog = Dialog.NONE
                        },
                        dialogTitle = "Clear Paired Device Entry",
                        dialogText = "This will delete the currently paired Home Assistant server, stop the satellite and allow another server to connect and pair to this device.",
                        confirmText = "Confirm",
                        dismissText = "Cancel",
                    )
                }
            }
        }
    }
}

