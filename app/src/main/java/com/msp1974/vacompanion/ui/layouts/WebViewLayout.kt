package com.msp1974.vacompanion.ui.layouts

import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.msp1974.vacompanion.satellite.VacaTimerUiState
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.DiagnosticBar
import com.msp1974.vacompanion.ui.components.IconStatusBlock
import com.msp1974.vacompanion.utils.CustomWebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun WebViewScreen (webView: CustomWebView, vaViewModel: VAViewModel = viewModel()) {
    val vaUiState by vaViewModel.vacaState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        var modifier = Modifier
            .fillMaxSize()
            .background(if(vaUiState.satelliteRunning) Color.Black else MaterialTheme.colorScheme.background)

        if (vaUiState.isDND) {
            modifier = modifier.border(4.dp, Color.Red)
        }

        Box(modifier = modifier) {
            WebView(webView, swipeRefreshEnabled = vaViewModel.config!!.swipeRefresh)
        }

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

        if (  !vaUiState.isNetworkConnected) {
            IconStatusBlock(
                message = "Wifi Disconnected",
                icon = "nowifi",
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        vaUiState.timer?.let { timer ->
            TimerOverlay(
                timer = timer,
                onDismiss = { vaViewModel.dismissTimerAlert(timer.id) },
                modifier = Modifier.align(if (timer.isFinished) Alignment.Center else Alignment.TopEnd)
            )
        }
    }
}

@Composable
private fun TimerOverlay(
    timer: VacaTimerUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var now by remember(timer.id, timer.expiresAtElapsedRealtime, timer.isFinished) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }

    LaunchedEffect(timer.id, timer.expiresAtElapsedRealtime, timer.isFinished) {
        while (!timer.isFinished) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    val remainingSeconds = if (timer.isFinished) {
        0
    } else {
        max(0, ((timer.expiresAtElapsedRealtime - now + 999L) / 1000L).toInt())
    }
    val label = timer.name?.takeIf { it.isNotBlank() } ?: "Timer"

    Column(
        modifier = modifier
            .padding(16.dp)
            .background(
                if (timer.isFinished) Color(0xEE7A1D1D) else Color(0xDD101820),
                RoundedCornerShape(6.dp)
            )
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (timer.isFinished) "$label finished" else label,
            color = Color.White,
            style = if (timer.isFinished) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = formatTimerDuration(remainingSeconds),
            color = Color.White,
            style = if (timer.isFinished) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (timer.isFinished) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.padding(top = 14.dp)
            ) {
                Text("Dismiss")
            }
        }
    }
}

private fun formatTimerDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}


@Composable
fun WebView(
    webView: CustomWebView,
    modifier: Modifier = Modifier,
    swipeRefreshEnabled: Boolean = true,
) {
    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier
            .fillMaxSize(),
        factory = { context ->
            SwipeRefreshLayout(context).apply {
                setOnRefreshListener {
                    refreshScope.launch {
                        refreshing = true
                        webView.refresh()
                        delay(1500)
                        refreshing = false
                    }
                }
                if (webView.parent != null) {
                    (webView.parent as ViewGroup).removeView(webView)
                }
                addView(webView).apply {
                    tag = "vaWebView"
                }
            }
        },
        update = { view ->
            view.isRefreshing = refreshing
            view.isEnabled = swipeRefreshEnabled
        }
    )
}



