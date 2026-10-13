package com.msp1974.vacompanion.ui.layouts

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.DiagnosticBar
import com.msp1974.vacompanion.ui.components.IconStatusBlock
import com.msp1974.vacompanion.ui.components.NowPlayingOverlay
import com.msp1974.vacompanion.ui.components.QuickActionsSheet
import com.msp1974.vacompanion.utils.CustomWebView
import com.msp1974.vacompanion.utils.WebViewGestureDetector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen (webView: CustomWebView, vaViewModel: VAViewModel = viewModel()) {
    val vaUiState by vaViewModel.vacaState.collectAsState()
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val density = LocalDensity.current

    // WebViewScreen is only ever composed while the dashboard should actually be visible (see
    // MainActivity's screenBlank/satelliteRunning when-block) - use that composition lifecycle to
    // stop any WebView processing (including in-page video/WebRTC decode) as soon as it isn't,
    // rather than relying on the view merely being detached from the window.
    DisposableEffect(webView) {
        webView.onResume()
        webView.resumeTimers()
        onDispose {
            webView.onPause()
            webView.pauseTimers()
        }
    }

    LaunchedEffect(webView) {
        webView.setOnGestureListener(object : WebViewGestureDetector.OnGestureListener {
            override fun onSwipe(
                event: WebViewGestureDetector.GestureEvent
            ) {
                if (event.pointers == 2 && event.direction == WebViewGestureDetector.Direction.BOTTOM_UP) {
                    // Check if it started near the bottom
                    showBottomSheet = true
                    vaViewModel.hideSystemUI()
                } else {
                    // Send the event to the ViewModel to send event to HA
                    vaViewModel.onGesture(event)
                }
            }

            override fun onLGesture(
                firstDir: WebViewGestureDetector.Direction,
                secondDir: WebViewGestureDetector.Direction
            ) {
            }
        })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        var modifier = Modifier
            .fillMaxSize()
            .background(if(vaUiState.satelliteRunning) Color.Black else MaterialTheme.colorScheme.background)

        if (vaUiState.isDND) {
            modifier = modifier.border(4.dp, Color.Red)
        }

        Box(modifier = modifier) {
            WebView(webView)
        }

        //TODO: Re-enable when have method for detecting full page render finished
        if (vaUiState.webViewPageLoadingStage != PageLoadingStage.LOADED && false) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Loading...", color = Color.White, fontSize = MaterialTheme.typography.headlineLarge.fontSize, textAlign = TextAlign.Center)
                }
            }
        }

        if (vaUiState.diagnosticInfo.show) {
            DiagnosticBar(
                vaUiState.diagnosticInfo,
                onEvent = { vaViewModel.onEvent(it) },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // Sendspin now-playing card, bottom-left so it stays clear of the status icons below.
        NowPlayingOverlay(
            nowPlaying = vaUiState.sendspinNowPlaying,
            onCommand = { vaViewModel.onSendspinCommand(it) },
            // Half the screen width (margin included), but never so narrow that the controls
            // and track details don't fit.
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(min = 300.dp)
                .fillMaxWidth(0.5f)
                .padding(start = 16.dp, bottom = 16.dp),
        )

        // Stacked (rather than overlapping) bottom-right status icons, in priority order.
        Column(modifier = Modifier.align(Alignment.BottomEnd)) {
            if (vaUiState.rtspClientCount > 0 && vaUiState.showRtspStreamingIndicator) {
                IconStatusBlock(
                    message = "Camera Streaming",
                    icon = "camera"
                )
            }

            // Wifi disconnection takes priority - if wifi is down, the server is necessarily
            // unreachable too, and showing both icons at once would just be redundant noise over
            // the one thing the user can actually act on.
            if (!vaUiState.isNetworkConnected) {
                IconStatusBlock(
                    message = "Wifi Disconnected",
                    icon = "nowifi"
                )
            } else if (!vaUiState.isServerConnected) {
                IconStatusBlock(
                    message = "Server Disconnected",
                    icon = "noserver"
                )
            }
        }

        if (showBottomSheet && vaViewModel.config.enableQuickActions) {
            QuickActionsSheet(
                onDismiss = { showBottomSheet = false },
                onReload = { webView.refresh() },
                onOpenSettings = { vaViewModel.onOpenSettingsAction() },
                sheetState = sheetState,
                isDiagnosticsEnabled = vaViewModel.config.diagnosticsEnabled,
                onToggleDiagnostics = { vaViewModel.onShowDiagnostics(
                    !vaViewModel.config.diagnosticsEnabled
                )},
                isDNDEnabled = vaUiState.isDND,
                onToggleDND = { vaViewModel.onToggleDND(!vaUiState.isDND) },
                isBluetoothMicEnabled = vaUiState.isBluetoothMicEnabled,
                onToggleBluetoothMic = { vaViewModel.onToggleBluetoothMic(!vaUiState.isBluetoothMicEnabled) },
                isBluetoothMicConnected = vaUiState.isBluetoothMicConnected
            )
        }
    }
}


@Composable
fun WebView(
    webView: CustomWebView,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier
            .fillMaxSize(),
        factory = {
            if (webView.parent != null) {
                (webView.parent as ViewGroup).removeView(webView)
            }
            webView.apply {
                tag = "vaWebView"
            }
        }
    )
}



