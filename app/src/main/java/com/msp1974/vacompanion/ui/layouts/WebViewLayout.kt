package com.msp1974.vacompanion.ui.layouts

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        if (!vaUiState.isNetworkConnected) {
            IconStatusBlock(
                message = "Wifi Disconnected",
                icon = "nowifi",
                modifier = Modifier.align(Alignment.BottomCenter)
            )
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
                onToggleDND = { vaViewModel.onToggleDND(!vaUiState.isDND) }
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



