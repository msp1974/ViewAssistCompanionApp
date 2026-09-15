package com.msp1974.vacompanion.ui.layouts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisabledByDefault
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.zIndex
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout
import com.msp1974.vacompanion.ui.components.MenuOption
import com.msp1974.vacompanion.ui.components.UUIDEditDialog
import com.msp1974.vacompanion.ui.components.VADialog

/**
 * Screen identifiers for the settings navigation.
 */
enum class SettingsScreen {
    MAIN,
    CUSTOM_FILES,
    PERMISSIONS_INFO,
    CAMERA_STREAM,
    DEVICE_INFO
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
                    val menuOptions = mutableListOf<MenuOption>()

                    if (!vaUiState.menuOpenedByAction) {
                        menuOptions.add(
                            MenuOption(
                                title = "Unpair Device",
                                subtitle = "Unpair from current HA server",
                                icon = Icons.Default.DisabledByDefault,
                                onClick = { currentDialog = Dialog.CLEAR_PAIRING }
                            )
                        )
                        menuOptions.add(
                            MenuOption(
                                title = "Edit Device ID",
                                subtitle = "Change the unique ID for this device",
                                icon = Icons.Default.FileCopy,
                                onClick = { currentDialog = Dialog.EDIT_UUID }
                            )
                        )
                    }

                    menuOptions.add(
                        MenuOption(
                            title = "Voice Enrollment",
                            subtitle = if (vaUiState.diagnosticInfo.hasSpeakerEnrollment) {
                                "Enabled"
                            } else {
                                "Disabled"
                            },
                            icon = Icons.Default.Security,
                            onClick = {}
                        )
                    )

                    menuOptions.add(
                        MenuOption(
                            title = "Enroll Speaker",
                            subtitle = vaUiState.speakerEnrollmentStatus.ifBlank {
                                "Capture voice profile for Sherpa speaker verification"
                            },
                            icon = Icons.Default.Security,
                            onClick = { viewModel.startSpeakerEnrollment() }
                        )
                    )

                    if (vaUiState.diagnosticInfo.hasSpeakerEnrollment) {
                        menuOptions.add(
                            MenuOption(
                                title = "Remove Speaker Enrollment",
                                subtitle = "Delete saved speaker embedding",
                                icon = Icons.Default.DisabledByDefault,
                                onClick = { viewModel.clearSpeakerEnrollment() }
                            )
                        )
                    }

                    menuOptions.add(
                        MenuOption(
                            title = "Manage Custom Files",
                            subtitle = "Manage custom wake words, sounds and alarms",
                            icon = Icons.Default.FileCopy,
                            onClick = { currentScreen = SettingsScreen.CUSTOM_FILES }
                        )
                    )
                    menuOptions.add(
                        MenuOption(
                            title = "Permissions Info",
                            icon = Icons.Default.Security,
                            onClick = { currentScreen = SettingsScreen.PERMISSIONS_INFO }
                        )
                    )
                    if (vaUiState.menuOpenedByAction && viewModel.deviceInfo.hardware.hasFrontCamera) {
                        menuOptions.add(
                            MenuOption(
                                title = "View Camera Stream",
                                subtitle = "Check the camera feed for motion or face detection",
                                icon = Icons.Default.Videocam,
                                onClick = { currentScreen = SettingsScreen.CAMERA_STREAM }
                            )
                        )
                    }
                    menuOptions.add(
                        MenuOption(
                            title = "Device Info",
                            subtitle = "View device capabilities and sensors",
                            icon = Icons.Default.Info,
                            onClick = { currentScreen = SettingsScreen.DEVICE_INFO }
                        )
                    )

                    MenuLayout(
                        title = "VACA Settings",
                        onClose = onClose,
                        options = menuOptions
                    )
                }
                SettingsScreen.CUSTOM_FILES -> {
                    CustomFilesLayout(
                        viewModel = viewModel,
                        onBack = { currentScreen = SettingsScreen.MAIN }
                    )
                }
                SettingsScreen.PERMISSIONS_INFO -> {
                    PermissionsLayout(
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
                SettingsScreen.DEVICE_INFO -> {
                    DeviceInfoLayout(
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
                            viewModel.clearPairedDevice()
                            currentDialog = Dialog.NONE
                        },
                        dialogTitle = "Clear Paired Device Entry",
                        dialogText = "This will delete the currently paired Home Assistant server and allow another server to connect and pair to this device.",
                        confirmText = "Confirm",
                        dismissText = "Cancel",
                    )
                }
            }
        }
    }
}
