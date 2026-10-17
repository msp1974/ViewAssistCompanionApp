package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.device.info.AudioDeviceSummary
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout

/**
 * On-device configuration screen, reached from the main Settings menu: audio input/output
 * device selectors (see APPConfig.audioInputId/audioOutputId and AudioInRouter/
 * AudioOutputRouter, which resolve them into an actual device to use) and the camera streaming
 * indicator toggle.
 */
@Composable
fun ConfigurationLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vaUiState by viewModel.vacaState.collectAsState()
    val deviceInfo = viewModel.deviceInfo

    MenuLayout(
        title = "Configuration",
        level = 1,
        onClose = onBack,
        modifier = modifier
    ) {
        SectionHeader("Audio Input")
        AudioDeviceDropdown(
            label = "Input device",
            devices = deviceInfo.hardware.audioInputs,
            selectedId = vaUiState.audioInputId,
            onSelect = { viewModel.setAudioInputId(it) }
        )

        SectionHeader("Audio Output")
        AudioDeviceDropdown(
            label = "Output device",
            devices = deviceInfo.hardware.audioOutputs,
            selectedId = vaUiState.audioOutputId,
            onSelect = { viewModel.setAudioOutputId(it) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader("Sendspin")
        SwitchOptionItem(
            label = "Sendspin Player",
            subtitle = "Let Music Assistant play synchronised audio on this device",
            checked = vaUiState.sendspinEnabled,
            icon = Icons.Default.Speaker,
            onCheckedChange = { viewModel.onToggleSendspin(it) }
        )
        SwitchOptionItem(
            label = "Media Player Overlay",
            subtitle = "Show the now-playing player on screen while Sendspin audio plays",
            checked = vaUiState.sendspinOverlayEnabled,
            icon = Icons.Default.MusicNote,
            onCheckedChange = { viewModel.onToggleSendspinOverlay(it) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader("Bluetooth Proxy")
        SwitchOptionItem(
            label = "Bluetooth Proxy",
            subtitle = "Let Home Assistant use this device's Bluetooth to reach BLE devices (add it via the ESPHome integration, no encryption key)",
            checked = vaUiState.bluetoothProxyEnabled,
            icon = Icons.Default.Bluetooth,
            onCheckedChange = { viewModel.onToggleBluetoothProxy(it) }
        )

        if (deviceInfo.hardware.hasFrontCamera) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("Camera")
            SwitchOptionItem(
                label = "Camera Streaming Icon",
                subtitle = "Shown on screen while the camera stream is active",
                checked = vaUiState.showRtspStreamingIndicator,
                icon = Icons.Default.Videocam,
                onCheckedChange = { viewModel.onToggleRtspStreamingIndicator(it) }
            )
            RotationDropdown(
                label = "Camera image rotation",
                selectedDegrees = vaUiState.cameraRotationOffset,
                onSelect = { viewModel.setCameraRotationOffset(it) }
            )
        }
    }
}

@Composable
private fun SwitchOptionItem(
    label: String,
    subtitle: String,
    checked: Boolean,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun deviceLabel(device: AudioDeviceSummary): String = "${device.productName} (${device.typeName})"

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * Extra rotation for the camera image (the RTSP stream and the on-device preview), for a device
 * that still shows it the wrong way up after the automatic orientation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RotationDropdown(
    label: String,
    selectedDegrees: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(0, 90, 180, 270)

    fun optionLabel(degrees: Int) = if (degrees == 0) "Automatic" else "Automatic + $degrees\u00B0"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = optionLabel(selectedDegrees),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            supportingText = { Text("Use if the camera feed is the wrong way up") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { degrees ->
                DropdownMenuItem(
                    text = { Text(optionLabel(degrees)) },
                    onClick = {
                        onSelect(degrees)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioDeviceDropdown(
    label: String,
    devices: List<AudioDeviceSummary>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = devices.firstOrNull { it.id == selectedId }?.let { deviceLabel(it) } ?: "Automatic"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Automatic") },
                onClick = {
                    onSelect(-1)
                    expanded = false
                }
            )
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(deviceLabel(device)) },
                    onClick = {
                        onSelect(device.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
