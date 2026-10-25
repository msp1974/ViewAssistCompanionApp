package com.msp1974.vacompanion.ui.layouts

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.State
import com.msp1974.vacompanion.ui.components.AppFloatingActionButton
import com.msp1974.vacompanion.ui.theme.AppTheme
import com.msp1974.vacompanion.ui.theme.CustomColours

@Composable
fun ConnectionScreen(vaViewModel: VAViewModel = viewModel()) {
    val vaUiState by vaViewModel.vacaState.collectAsState()
    var showPermissions by remember { mutableStateOf(false) }

    if (vaUiState.showSettings) {
        SettingsLayout(
            vaViewModel,
            { vaViewModel.setShowSettings(false) }
        )
    } else if (showPermissions) {
        PermissionsLayout(
            viewModel = vaViewModel,
            onBack = { showPermissions = false }
        )
    } else {
        ConnectionContent(
            vaUiState = vaUiState,
            onSettingsClick = { vaViewModel.setShowSettings(true) },
            onCheckForUpdate = { vaViewModel.checkForUpdate() },
            onPermissionsClick = { showPermissions = true },
        )
    }
}

@Composable
fun ConnectionContent(
    vaUiState: State,
    onSettingsClick: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onPermissionsClick: () -> Unit,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val details: @Composable () -> Unit = {
        InfoCard(vaUiState.appInfo)
        Spacer(modifier = Modifier.height(16.dp))
        LaunchOnBootHint()
        if (vaUiState.updates.updateAvailable || !vaUiState.permissions.hasCorePermissions || !vaUiState.permissions.hasOptionalPermissions) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (vaUiState.updates.updateAvailable) {
                    UpdateButton(
                        text = stringResource(R.string.button_update_required),
                        onClick = onCheckForUpdate
                    )
                }
                if (!vaUiState.permissions.hasCorePermissions || !vaUiState.permissions.hasOptionalPermissions) {
                    PermissionStatusButton(
                        text = "Permissions",
                        colour = if (!vaUiState.permissions.hasCorePermissions) CustomColours.RED else CustomColours.AMBER,
                        onClick = onPermissionsClick
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                Box(modifier = Modifier.weight(0.4f), contentAlignment = Alignment.Center) {
                    LogoImage(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                Column(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) { details() }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                LogoImage(Modifier.padding(vertical = 8.dp).fillMaxWidth(0.5f))
                Spacer(modifier = Modifier.height(40.dp))
                details()
                Spacer(modifier = Modifier.height(120.dp)) // Space for Status and FAB
            }
        }

        StatusText(
            statusMessage = vaUiState.statusMessage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (vaUiState.clockSynchronised) 0.dp else 56.dp)
        )

        if (!vaUiState.clockSynchronised) {
            ClockWarningBanner(modifier = Modifier.align(Alignment.BottomCenter))
        }

        AppFloatingActionButton(
            onClick = onSettingsClick,
            modifier = Modifier.align(Alignment.BottomEnd),
            icon = Icons.Default.Settings,
            contentDescription = "Settings"
        )
    }
}

@Composable
fun LogoImage(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.main_logo),
        contentDescription = "Logo",
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
        modifier = modifier
    )
}

/** Device/app details. Values never wrap: they stay on one line and ellipsize only as a last resort. */
@Composable
fun InfoCard(infoItems: Map<String, String>) {
    // In light mode the background is a mid blue, so the card is a lighter shade of it rather than
    // the near-white surface colour, which is too stark against it.
    val background = MaterialTheme.colorScheme.background
    val isLight = background.luminance() > 0.18f
    val cardColor = if (isLight) lerp(background, Color.White, 0.3f) else MaterialTheme.colorScheme.surfaceContainer
    val labelColor = if (isLight) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardColor,
        modifier = Modifier
            .widthIn(min = 280.dp, max = 520.dp)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            infoItems.entries.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = labelColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusText(statusMessage: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 24.dp, start = 80.dp, end = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            statusMessage,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun ClockWarningBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(CustomColours.RED)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 80.dp, top = 12.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "The system clock is not synchronised to a time server. This may cause authentication to fail.",
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun LaunchOnBootHint() {
    Text(
        text = "To launch on boot, set this app as the launcher in the configuration menu",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
fun UpdateButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = { onClick() },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = CustomColours.AMBER,
            contentColor = Color.White,
        )
    ) { Text(text) }
}

@Composable
fun PermissionStatusButton(text: String, colour: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = { onClick() },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = colour
        )
    ) { Text(text) }
}

@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    name = "DefaultPreviewDark",
    apiLevel = 36,
    heightDp = 1024,
    widthDp = 480,
)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    name = "DefaultPreviewLight"
)
@Preview(heightDp = 480, widthDp = 800)
@Composable
fun AppPreview() {
    AppTheme(
        dynamicColor = false,
        darkMode = false
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ConnectionContent(
                vaUiState = State(
                    appInfo = mapOf(
                        "Version" to "1.0.0",
                        "IP Address" to "192.168.1.100",
                        "Port" to "8080",
                        "UUID" to "1234-5678",
                        "Paired to" to "Home Assistant"
                    )
                ),
                onSettingsClick = {},
                onCheckForUpdate = {},
                onPermissionsClick = {},
            )
        }
    }
}

