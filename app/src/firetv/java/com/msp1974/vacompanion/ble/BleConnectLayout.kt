package com.msp1974.vacompanion.ui.layouts

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.SignalCellular0Bar
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.ui.theme.AppTheme
import com.msp1974.vacompanion.ui.theme.CustomColours

// ---------------------------------------------------------------------------
// Public state + callbacks contract (passed in from Activity)
// ---------------------------------------------------------------------------

data class BleDeviceRow(val name: String, val mac: String, val rssi: Int)

data class BleConnectUiState(
    val scanning: Boolean = false,
    val hasLastRemote: Boolean = false,
    val lastRemoteName: String? = null,
    val reconnecting: Boolean = false,
    val devices: List<BleDeviceRow> = emptyList(),
    val selectedMac: String? = null,
    val countdownSecs: Int? = null,
)

// ---------------------------------------------------------------------------
// Root screen – orientation-aware
// ---------------------------------------------------------------------------

@Composable
fun BleConnectScreen(
    uiState: BleConnectUiState,
    onScan: () -> Unit,
    onSkip: () -> Unit,
    onForget: () -> Unit,
    onDeviceSelect: (BleDeviceRow) -> Unit,
) {
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation

    when (orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> {
            BleConnectLandscape(uiState, onScan, onSkip, onForget, onDeviceSelect)
        }
        else -> {
            BleConnectPortrait(uiState, onScan, onSkip, onForget, onDeviceSelect)
        }
    }
}

// ---------------------------------------------------------------------------
// Portrait layout
// ---------------------------------------------------------------------------

@Composable
private fun BleConnectPortrait(
    uiState: BleConnectUiState,
    onScan: () -> Unit,
    onSkip: () -> Unit,
    onForget: () -> Unit,
    onDeviceSelect: (BleDeviceRow) -> Unit,
) {
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .safeDrawingPadding()
            .background(MaterialTheme.colorScheme.background)
            .fillMaxSize(),
    ) {
        BleHeader(uiState)

        SectionLabel(text = "NEARBY DEVICES")

        Box(modifier = Modifier.weight(1f)) {
            if (uiState.devices.isEmpty()) {
                BleEmptyState(uiState.scanning)
            } else {
                DeviceList(
                    devices = uiState.devices,
                    onDeviceSelect = onDeviceSelect,
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        BleActionBar(
            uiState = uiState,
            onScan = onScan,
            onSkip = onSkip,
            onForget = onForget,
        )
    }
}

// ---------------------------------------------------------------------------
// Landscape layout
// ---------------------------------------------------------------------------

@Composable
private fun BleConnectLandscape(
    uiState: BleConnectUiState,
    onScan: () -> Unit,
    onSkip: () -> Unit,
    onForget: () -> Unit,
    onDeviceSelect: (BleDeviceRow) -> Unit,
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            BleHeader(uiState)
            Spacer(modifier = Modifier.weight(1f))
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            BleActionBar(
                uiState = uiState,
                onScan = onScan,
                onSkip = onSkip,
                onForget = onForget,
            )
        }

        Divider(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxSize(),
        ) {
            SectionLabel(text = "NEARBY DEVICES")
            Box(modifier = Modifier.weight(1f)) {
                if (uiState.devices.isEmpty()) {
                    BleEmptyState(uiState.scanning)
                } else {
                    DeviceList(
                        devices = uiState.devices,
                        onDeviceSelect = onDeviceSelect,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun BleHeader(uiState: BleConnectUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text(
            text = "BLE REMOTE",
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                uiState.reconnecting && uiState.lastRemoteName != null ->
                    "Reconnecting to ${uiState.lastRemoteName}"
                uiState.reconnecting -> "Reconnecting…"
                uiState.hasLastRemote -> "Last Remote Detected"
                else -> "Connect a Remote"
            },
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                uiState.reconnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Waiting for device advertisement…",
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                    )
                }
                uiState.scanning -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Searching for BLE remotes…",
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.devices.isEmpty()) "Not scanning"
                        else "${uiState.devices.size} device(s) found",
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section label
// ---------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
    )
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
private fun BleEmptyState(scanning: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
        Icon(
            imageVector = Icons.Default.BluetoothSearching,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = if (scanning) alpha else 0.25f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (scanning) "Scanning…" else "No devices found",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (scanning) "Looking for BLE remotes nearby" else "Press SCAN to search for BLE remotes",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Device list
// ---------------------------------------------------------------------------

@Composable
private fun DeviceList(
    devices: List<BleDeviceRow>,
    onDeviceSelect: (BleDeviceRow) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(devices, key = { it.mac }) { device ->
            DeviceItem(
                device = device,
                onClick = { onDeviceSelect(device) },
            )
        }
    }
}

@Composable
private fun DeviceItem(
    device: BleDeviceRow,
    onClick: () -> Unit,
) {
    // Keep track of TV D-pad focus so the user knows what they are about to click
    var isDpadFocused by remember { mutableStateOf(false) }

    val borderColor = if (isDpadFocused) MaterialTheme.colorScheme.primary else Color.Transparent

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isDpadFocused = it.isFocused }
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDpadFocused) 4.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = rssiIcon(device.rssi),
                    contentDescription = "Signal strength",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = device.mac,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Text(
                text = "${device.rssi} dBm",
                color = rssiColor(device.rssi),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Action bar
// ---------------------------------------------------------------------------

@Composable
private fun BleActionBar(
    uiState: BleConnectUiState,
    onScan: () -> Unit,
    onSkip: () -> Unit,
    onForget: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // Expanded Scan button to fill the space left by the removed Connect button
        OutlinedButton(
            onClick = onScan,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (uiState.scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (uiState.scanning) "SCANNING" else "SCAN",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                ),
            ) {
                Text(
                    text = uiState.countdownSecs?.let { "Skip (${it}s)" } ?: "Skip",
                    fontSize = 12.sp,
                )
            }

            TextButton(
                onClick = onForget,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = CustomColours.RED,
                ),
            ) {
                Text(text = "Forget All", fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers & Previews
// ---------------------------------------------------------------------------

@Composable
private fun rssiColor(rssi: Int): Color = when {
    rssi >= -60 -> Color(0xFF2E7D32)   // strong
    rssi >= -75 -> Color(0xFFF57F17)   // medium
    else        -> CustomColours.RED   // weak
}

private fun rssiIcon(rssi: Int) = when {
    rssi >= -60 -> Icons.Default.SignalCellular4Bar
    rssi >= -75 -> Icons.Default.SignalCellularAlt
    else        -> Icons.Default.SignalCellular0Bar
}

private val previewState = BleConnectUiState(
    scanning = false,
    hasLastRemote = true,
    lastRemoteName = "VA Remote 1",
    reconnecting = false,
    devices = listOf(
        BleDeviceRow("VA Remote 1", "AA:BB:CC:DD:EE:FF", -55),
        BleDeviceRow("VA Remote 2", "11:22:33:44:55:66", -72),
        BleDeviceRow("Unknown Device", "AA:11:BB:22:CC:33", -88),
    ),
    selectedMac = "AA:BB:CC:DD:EE:FF",
    countdownSecs = 4,
)

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "BleConnect Dark", apiLevel = 36)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO, name = "BleConnect Light")
@Composable
private fun BleConnectPortraitPreview() {
    AppTheme(dynamicColor = false, darkMode = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BleConnectScreen(uiState = previewState, onScan = {}, onSkip = {}, onForget = {}, onDeviceSelect = {})
        }
    }
}

@Preview(heightDp = 480, widthDp = 800, name = "BleConnect Landscape")
@Composable
private fun BleConnectLandscapePreview() {
    AppTheme(dynamicColor = false, darkMode = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BleConnectScreen(uiState = previewState, onScan = {}, onSkip = {}, onForget = {}, onDeviceSelect = {})
        }
    }
}