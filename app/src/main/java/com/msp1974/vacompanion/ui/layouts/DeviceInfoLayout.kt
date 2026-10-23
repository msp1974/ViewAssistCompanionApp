package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context
import android.media.AudioManager
import com.msp1974.vacompanion.audio.AudioEnhancerSource
import com.msp1974.vacompanion.audio.AudioInRouter
import com.msp1974.vacompanion.audio.AudioOutputRouter
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

private fun activeOutputLabel(context: Context, config: APPConfig): String {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val id = AudioOutputRouter.activeOutputId(context, config)
    return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.id == id }
        ?.let { "${it.productName} (${AudioInRouter.getDeviceTypeName(it.type)})" }
        ?: "Unknown"
}

// Running is green; enabled but not up is red, since something is wrong; otherwise it is simply off.
@Composable
private fun ServiceStatusItem(icon: ImageVector, label: String, running: Boolean, enabled: Boolean) {
    InfoListItem(
        icon = icon,
        label = label,
        value = when {
            running -> "Running"
            enabled -> "Not running"
            else -> "Disabled"
        },
        valueColor = when {
            running -> CustomColours.GREEN
            enabled -> CustomColours.RED
            else -> CustomColours.AMBER
        }
    )
}

@Composable
fun DeviceInfoLayout(
    viewModel: VAViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vaUiState by viewModel.vacaState.collectAsState()
    val deviceInfo = remember { viewModel.deviceInfo }
    val context = LocalContext.current

    // Worked out afresh when the selected output changes or an output is connected or removed.
    val activeOutput = remember(vaUiState.audioOutputId, vaUiState.sensorState.audioOutput) {
        activeOutputLabel(context, viewModel.config)
    }

    val audioEnhancementsSupported = deviceInfo.features.audio.autoGainControl ||
        deviceInfo.features.audio.noiseSuppression ||
        deviceInfo.features.audio.acousticEchoCancellation

    val columns = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 2 else 1

    MenuLayout(
        title = "Device Info",
        level = 1,
        onClose = onBack,
        modifier = modifier
    ) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalItemSpacing = 16.dp
        ) {
            item {
                SectionCard("Software") {
                    InfoListItem(
                        icon = Icons.Default.Smartphone,
                        label = "Device",
                        value = deviceInfo.hardware.deviceName
                    )
                    InfoListItem(
                        icon = Icons.Default.Info,
                        label = "App Version",
                        value = deviceInfo.software.appVersion
                    )
                    InfoListItem(
                        icon = Icons.Default.Android,
                        label = "Android Version",
                        value = deviceInfo.software.release
                    )
                    InfoListItem(
                        icon = Icons.Default.Code,
                        label = "WebView Version",
                        value = deviceInfo.software.webViewVersion
                    )
                }
            }
            item {
                SectionCard("Hardware Capabilities") {
                    InfoListItem(
                        icon = Icons.Default.BatteryFull,
                        label = "Battery",
                        value = if (deviceInfo.hardware.hasBattery) "Yes" else "No",
                        valueColor = if (deviceInfo.hardware.hasBattery) CustomColours.GREEN else CustomColours.AMBER
                    )
                    InfoListItem(
                        icon = Icons.Default.CameraAlt,
                        label = "Front Camera",
                        value = if (deviceInfo.hardware.hasFrontCamera) "Yes" else "No",
                        valueColor = if (deviceInfo.hardware.hasFrontCamera) CustomColours.GREEN else CustomColours.AMBER
                    )
                    InfoListItem(
                        icon = Icons.Default.SettingsSuggest,
                        label = "DND Support",
                        value = if (deviceInfo.features.supportsDND) "Yes" else "No",
                        valueColor = if (deviceInfo.features.supportsDND) CustomColours.GREEN else CustomColours.AMBER
                    )
                }
            }
            item {
                SectionCard("Sensors") {

                    InfoListItem(
                        icon = Icons.Default.Sensors,
                        label = "Accelerometer",
                        value = if (deviceInfo.hardware.hasAccelerometer) "Yes" else "No",
                        valueColor = if (deviceInfo.hardware.hasAccelerometer) CustomColours.GREEN else CustomColours.AMBER
                    )
                    InfoListItem(
                        icon = Icons.Default.LightMode,
                        label = "Light Sensor",
                        value = if (deviceInfo.hardware.hasLightSensor) "Yes" else "No",
                        valueColor = if (deviceInfo.hardware.hasLightSensor) CustomColours.GREEN else CustomColours.AMBER
                    )
                    InfoListItem(
                        icon = Icons.Default.Radar,
                        label = "Proximity Sensor",
                        value = if (deviceInfo.hardware.hasProximitySensor) "Yes" else "No",
                        valueColor = if (deviceInfo.hardware.hasProximitySensor) CustomColours.GREEN else CustomColours.AMBER
                    )
                if (deviceInfo.hardware.hasProximitySensor) {
                        InfoListItem(
                            icon = Icons.AutoMirrored.Filled.Label,
                            label = "Proximity Type",
                            value = deviceInfo.hardware.proximitySensorType.replaceFirstChar { it.uppercase() }
                        )
                    }
            }
            }
            item {
                SectionCard("Audio") {

                    InfoListItem(
                        icon = Icons.Default.GraphicEq,
                        label = "Audio Enhancements Supported",
                        value = if (audioEnhancementsSupported) "Yes" else "No",
                        valueColor = if (audioEnhancementsSupported) CustomColours.GREEN else CustomColours.AMBER
                    )

                    InfoListItem(
                        icon = Icons.Default.Equalizer,
                        label = "Automatic Gain Control",
                        value = enhancementSourceLabel(MicrophoneInput.agcSource),
                        valueColor = enhancementSourceColor(MicrophoneInput.agcSource)
                    )

                    InfoListItem(
                        icon = Icons.Default.NoiseAware,
                        label = "Noise Suppression",
                        value = enhancementSourceLabel(MicrophoneInput.nsSource),
                        valueColor = enhancementSourceColor(MicrophoneInput.nsSource)
                    )

                    InfoListItem(
                        icon = Icons.Default.PhoneInTalk,
                        label = "Echo Cancellation",
                        value = enhancementSourceLabel(MicrophoneInput.aecSource),
                        valueColor = enhancementSourceColor(MicrophoneInput.aecSource)
                    )

                    InfoListItem(
                        icon = Icons.Default.Mic,
                        label = "Active Microphone",
                        value = vaUiState.sensorState.micInput?.activeInput ?: "Unknown"
                    )

                    InfoListItem(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        label = "Active Audio Output",
                        value = activeOutput
                    )

                    InfoListItem(
                        icon = Icons.Default.SettingsVoice,
                        label = "Available Microphones",
                        value = vaUiState.sensorState.micInput?.availableInputs?.joinToString(", ") ?: "None"
                    )
                }
            }
            item {
                SectionCard("Services") {
                    ServiceStatusItem(
                        icon = Icons.Default.Videocam,
                        label = "RTSP Server",
                        running = vaUiState.rtspServerRunning,
                        enabled = viewModel.config.rtspEnabled
                    )
                    ServiceStatusItem(
                        icon = Icons.Default.Videocam,
                        label = "WebRTC Server",
                        running = vaUiState.webrtcServerRunning,
                        enabled = viewModel.config.webrtcEnabled
                    )
                    ServiceStatusItem(
                        icon = Icons.Default.Speaker,
                        label = "Sendspin Player",
                        running = vaUiState.sendspinRunning,
                        enabled = viewModel.config.sendspinEnabled
                    )
                    ServiceStatusItem(
                        icon = Icons.Default.Bluetooth,
                        label = "Bluetooth Proxy",
                        running = vaUiState.bluetoothProxyRunning,
                        enabled = viewModel.config.bluetoothProxyEnabled
                    )
                }
            }
            item {
                SectionCard("Project") {
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
}

// One group of related items in a rounded card, with its title above the items.
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            content()
        }
    }
}

// The icon on the left with the name above its value beside it.
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
                color = MaterialTheme.colorScheme.onSurface,
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
