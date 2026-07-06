package com.msp1974.vacompanion.ui.layouts

import android.content.res.Configuration
import android.os.Build
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
import com.msp1974.vacompanion.ui.components.InfoItem
import com.msp1974.vacompanion.ui.components.LabelledSwitch
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
            onLaunchOnBootChange = { vaViewModel.launchOnBoot = it },
            onCheckForUpdate = { vaViewModel.checkForUpdate() },
            onPermissionsClick = { showPermissions = true },
        )
    }
}

@Composable
fun ConnectionContent(
    vaUiState: State,
    onSettingsClick: () -> Unit,
    onLaunchOnBootChange: (Boolean) -> Unit,
    onCheckForUpdate: () -> Unit,
    onPermissionsClick: () -> Unit,
) {
    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    ) {
        when(val orientation = LocalConfiguration.current.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                Column() {
                    Column(
                        modifier = Modifier.weight(0.15f)
                    ) {
                        // Action Buttons in Landscape
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (vaUiState.updates.updateAvailable) {
                                UpdateButton(
                                    text = stringResource(R.string.button_update_required),
                                    modifier = Modifier.padding(8.dp),
                                    onClick = onCheckForUpdate
                                )
                            }
                            if (!vaUiState.permissions.hasCorePermissions || !vaUiState.permissions.hasOptionalPermissions) {
                                PermissionStatusButton(
                                    text = "Permissions",
                                    colour = if (!vaUiState.permissions.hasCorePermissions) CustomColours.RED else CustomColours.AMBER,
                                    modifier = Modifier.padding(8.dp),
                                    onClick = onPermissionsClick
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .safeDrawingPadding()
                            .weight(0.85f)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.4f)
                                .fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LogoImage(orientation)
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.6f)
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            InfoTextBlock(vaUiState.appInfo)

                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                                LaunchOnBootSwitch(
                                    vaUiState.launchOnBoot,
                                    callback = onLaunchOnBootChange
                                )
                            } else {
                                Text(
                                    text = "To launch on boot, set this app as the launcher",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .width(280.dp)
                                        .padding(16.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.weight(0.1f)
                    ) { }
                }
            }
            else -> { // Portrait and Default
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(40.dp))
                    
                    LogoImage(orientation)
                    
                    Spacer(modifier = Modifier.height(40.dp))
                    
                    InfoTextBlock(vaUiState.appInfo)
                    
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                        LaunchOnBootSwitch(vaUiState.launchOnBoot, callback = onLaunchOnBootChange)
                    } else {
                        Text(
                            text="To launch on boot, set this app as the launcher",
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .width(280.dp)
                                .padding(16.dp),
                            textAlign = TextAlign.Center
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (vaUiState.updates.updateAvailable) {
                            UpdateButton(
                                text = stringResource(R.string.button_update_required),
                                modifier = Modifier.padding(vertical = 8.dp),
                                onClick = onCheckForUpdate)
                        }
                        if (!vaUiState.permissions.hasCorePermissions || !vaUiState.permissions.hasOptionalPermissions) {
                            PermissionStatusButton(
                                text = "Permissions",
                                colour = if (!vaUiState.permissions.hasCorePermissions) CustomColours.RED else CustomColours.AMBER,
                                modifier = Modifier.padding(vertical = 8.dp),
                                onClick = onPermissionsClick
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(100.dp)) // Space for Status and FAB
                }
            }
        }

        // Fixed elements at the bottom
        StatusText(
            statusMessage = vaUiState.statusMessage,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        AppFloatingActionButton(
            onClick = onSettingsClick,
            modifier = Modifier.align(Alignment.BottomEnd),
            icon = Icons.Default.Settings,
            contentDescription = "Settings"
        )
    }
}

@Composable
fun LogoImage(orientation: Int) {
    Image(
        painter = painterResource(id = R.drawable.main_logo),
        contentDescription = "Logo",
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.surfaceBright),
        modifier =
                when(orientation) {
                    Configuration.ORIENTATION_PORTRAIT -> Modifier.padding(start=48.dp, end=48.dp, top=8.dp)
                    else -> Modifier.padding(start=24.dp, end=24.dp, top=8.dp)
                }
    )
}

@Composable
fun InfoTextBlock(infoItems: Map<String, String>) {
    Column(
        modifier=Modifier
            .width(280.dp)
            .padding(16.dp)
    ) {
        infoItems.forEach { (label, value) ->
            InfoItem(label, value)
        }
    }
}



@Composable
fun StatusText(statusMessage: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            statusMessage,
            color = MaterialTheme.colorScheme.onPrimary, 
            textAlign = TextAlign.Center,
            fontSize = 20.sp
        )
    }
}

@Composable
fun LaunchOnBootSwitch(isOn: Boolean, callback: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LabelledSwitch(isOn, callback)
    }
}

@Composable
fun UpdateButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = { onClick() },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = CustomColours.AMBER,
            contentColor = MaterialTheme.colorScheme.onPrimary,
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
                onLaunchOnBootChange = {},
                onCheckForUpdate = {},
                onPermissionsClick = {},
            )
        }
    }
}

