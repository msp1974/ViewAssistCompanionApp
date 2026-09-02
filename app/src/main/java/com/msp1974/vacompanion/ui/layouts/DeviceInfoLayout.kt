package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.audio.AudioEnhancerSource
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.MenuLayout
import com.msp1974.vacompanion.ui.theme.CustomColours

private fun enhancementSourceLabel(source: String): String = when (source) {
    AudioEnhancerSource.ANDROID -> "Android"
    AudioEnhancerSource.SOFTWARE -> "Software"
    else -> "Not Available"
}

private fun enhancementSourceColor(source: String): Color = when (source) {
    AudioEnhancerSource.ANDROID -> CustomColours.GREEN
    AudioEnhancerSource.SOFTWARE -> CustomColours.AMBER
    else -> CustomColours.RED
}

@Composable
fun DeviceInfoLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vaUiState by viewModel.vacaState.collectAsState()
    val deviceInfo = remember { viewModel.deviceInfo }

    val audioEnhancementsSupported = deviceInfo.features.audio.autoGainControl ||
        deviceInfo.features.audio.noiseSuppression ||
        deviceInfo.features.audio.acousticEchoCancellation

    MenuLayout(
        title = "Device Info",
        level = 1,
        onClose = onBack,
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { SectionHeader("Software") }
            item { 
                InfoListItem(
                    icon = Icons.Default.Smartphone,
                    label = "Device",
                    value = deviceInfo.hardware.deviceName
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.Info,
                    label = "App Version",
                    value = deviceInfo.software.appVersion
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.Android,
                    label = "Android Version",
                    value = deviceInfo.software.release
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.Code,
                    label = "WebView Version",
                    value = deviceInfo.software.webViewVersion
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Hardware Capabilities") }
            
            item { 
                InfoListItem(
                    icon = Icons.Default.BatteryFull,
                    label = "Battery",
                    value = if (deviceInfo.hardware.hasBattery) "Yes" else "No",
                    valueColor = if (deviceInfo.hardware.hasBattery) CustomColours.GREEN else CustomColours.AMBER
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.CameraAlt,
                    label = "Front Camera",
                    value = if (deviceInfo.hardware.hasFrontCamera) "Yes" else "No",
                    valueColor = if (deviceInfo.hardware.hasFrontCamera) CustomColours.GREEN else CustomColours.AMBER
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.SettingsSuggest,
                    label = "DND Support",
                    value = if (deviceInfo.features.supportsDND) "Yes" else "No",
                    valueColor = if (deviceInfo.features.supportsDND) CustomColours.GREEN else CustomColours.AMBER
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Sensors") }

            item { 
                InfoListItem(
                    icon = Icons.Default.Sensors,
                    label = "Accelerometer",
                    value = if (deviceInfo.hardware.hasAccelerometer) "Yes" else "No",
                    valueColor = if (deviceInfo.hardware.hasAccelerometer) CustomColours.GREEN else CustomColours.AMBER
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.LightMode,
                    label = "Light Sensor",
                    value = if (deviceInfo.hardware.hasLightSensor) "Yes" else "No",
                    valueColor = if (deviceInfo.hardware.hasLightSensor) CustomColours.GREEN else CustomColours.AMBER
                )
            }
            item { 
                InfoListItem(
                    icon = Icons.Default.Radar,
                    label = "Proximity Sensor",
                    value = if (deviceInfo.hardware.hasProximitySensor) "Yes" else "No",
                    valueColor = if (deviceInfo.hardware.hasProximitySensor) CustomColours.GREEN else CustomColours.AMBER
                )
            }
            if (deviceInfo.hardware.hasProximitySensor) {
                item { 
                    InfoListItem(
                        icon = Icons.AutoMirrored.Filled.Label,
                        label = "Proximity Type",
                        value = deviceInfo.hardware.proximitySensorType.replaceFirstChar { it.uppercase() }
                    )
                }
            }

            item { 
                InfoListItem(
                    icon = Icons.Default.SettingsInputComponent,
                    label = "Available Sensors",
                    value = deviceInfo.hardware.sensors.joinToString(separator = ", ") { it.name.lowercase().replaceFirstChar { it.uppercase() } }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Audio") }

            item {
                InfoListItem(
                    icon = Icons.Default.GraphicEq,
                    label = "Audio Enhancements Supported",
                    value = if (audioEnhancementsSupported) "Yes" else "No",
                    valueColor = if (audioEnhancementsSupported) CustomColours.GREEN else CustomColours.AMBER
                )
            }

            item {
                InfoListItem(
                    icon = Icons.Default.Equalizer,
                    label = "Automatic Gain Control",
                    value = enhancementSourceLabel(MicrophoneInput.agcSource),
                    valueColor = enhancementSourceColor(MicrophoneInput.agcSource)
                )
            }

            item {
                InfoListItem(
                    icon = Icons.Default.NoiseAware,
                    label = "Noise Suppression",
                    value = enhancementSourceLabel(MicrophoneInput.nsSource),
                    valueColor = enhancementSourceColor(MicrophoneInput.nsSource)
                )
            }

            item {
                InfoListItem(
                    icon = Icons.Default.PhoneInTalk,
                    label = "Echo Cancellation",
                    value = enhancementSourceLabel(MicrophoneInput.aecSource),
                    valueColor = enhancementSourceColor(MicrophoneInput.aecSource)
                )
            }

            item {
                InfoListItem(
                    icon = Icons.Default.Mic,
                    label = "Active Microphone",
                    value = vaUiState.sensorState.micInput?.activeInput ?: "Unknown"
                )
            }

            item {
                InfoListItem(
                    icon = Icons.Default.SettingsVoice,
                    label = "Available Microphones",
                    value = vaUiState.sensorState.micInput?.availableInputs?.joinToString(", ") ?: "None"
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Project") }
            item {
                ListItem(
                    headlineContent = {
                        Text(
                            text = "Check for Updates",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "Check for latest version of this app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Update,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.clickable {
                        viewModel.checkForUpdate()
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            item {
                val uriHandler = LocalUriHandler.current
                ListItem(
                    headlineContent = {
                        Text(
                            text = "GitHub Repository",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "Link to this projects github repo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.clickable {
                        uriHandler.openUri(APPConfig.GITHUB_RELEASES_URL)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

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

@Composable
private fun InfoListItem(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
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
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
