package com.msp1974.vacompanion.ui.components

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.ui.theme.CustomColours
import com.msp1974.vacompanion.satellite.AudioRouteOption
import kotlinx.coroutines.delay
import java.lang.System


@SuppressLint("DefaultLocale")
@Composable
fun DiagnosticBar(
    diagnosticInfo: DiagnosticInfo,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .zIndex(2f)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.8f))
            .pointerInput(Unit) {
                // Prevent propagation of click
            }
    ) {
        if (isPortrait) {
            Column(
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InfoGauge(
                        canvasSize = 130.dp,
                        indicatorValue = diagnosticInfo.audioLevel,
                        maxIndicatorValue = 100,
                        smallText = "Mic",
                        foregroundIndicatorColor = CustomColours.GREEN,
                        disabledText = "Muted",
                        disabled = diagnosticInfo.muted
                    )
                    InfoGauge(
                        canvasSize = 130.dp,
                        indicatorValue = diagnosticInfo.detectionLevel,
                        maxIndicatorValue = 10,
                        decimalPlaces = 1,
                        smallText = "Wake",
                        foregroundIndicatorColor = if (diagnosticInfo.detectionLevel >= diagnosticInfo.detectionThreshold) CustomColours.GREEN else CustomColours.AMBER,
                        disabledText = "Off",
                        disabled = diagnosticInfo.wakeWord == "none"
                    )
                    if (diagnosticInfo.hasCamera && diagnosticInfo.motionDetectionMode != "none") {
                        MotionIndicator(diagnosticInfo)
                    }
                }
                DiagnosticChips(diagnosticInfo)
            }
        } else {
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (diagnosticInfo.hasCamera && diagnosticInfo.motionDetectionMode != "none") {
                    Column(
                        modifier = Modifier.padding(end = 10.dp)
                    ) {
                        MotionIndicator(diagnosticInfo)
                    }
                }
                Row {
                    InfoGauge(
                        indicatorValue = diagnosticInfo.audioLevel,
                        maxIndicatorValue = 100,
                        smallText = "Mic Level",
                        foregroundIndicatorColor = CustomColours.GREEN,
                        disabledText = "Muted",
                        disabled = diagnosticInfo.muted
                    )

                    InfoGauge(
                        indicatorValue = diagnosticInfo.detectionLevel,
                        maxIndicatorValue = 10,
                        decimalPlaces = 1,
                        smallText = "Detection",
                        foregroundIndicatorColor = if (diagnosticInfo.detectionLevel >= diagnosticInfo.detectionThreshold) CustomColours.GREEN else CustomColours.AMBER,
                        disabledText = "Disabled",
                        disabled = diagnosticInfo.wakeWord == "none"
                    )
                }

                Column(
                    modifier = Modifier.padding(start = 10.dp)
                ) {
                    DiagnosticChips(diagnosticInfo)
                }
            }
        }
    }
}

@Composable
private fun MotionIndicator(diagnosticInfo: DiagnosticInfo) {
    val motionDetected = diagnosticInfo.motionDetected
    val isFaceMode = diagnosticInfo.motionDetectionMode == "face"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = if (isFaceMode) Icons.Default.Face else Icons.AutoMirrored.Filled.DirectionsRun,
            contentDescription = if (isFaceMode) "Face Detected" else "Motion Detected",
            tint = if (motionDetected) CustomColours.GREEN else Color.Gray,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = if (isFaceMode) "Face" else "Motion",
            color = if (motionDetected) CustomColours.GREEN else Color.Gray,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun DiagnosticChips(diagnosticInfo: DiagnosticInfo) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val labelColour = Color.White

    if (isPortrait) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = {},
                label = { Text(if (diagnosticInfo.engine != "") diagnosticInfo.engine else "Disabled") },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = labelColour
                )
            )
            AssistChip(
                onClick = {},
                label = { Text("Detecting") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (diagnosticInfo.mode == AudioRouteOption.DETECT) CustomColours.GREEN else Color.Transparent,
                    labelColor = labelColour
                )
            )
            AssistChip(
                onClick = {},
                label = { Text("Streaming") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (diagnosticInfo.mode == AudioRouteOption.STREAM) CustomColours.GREEN else Color.Transparent,
                    labelColor = labelColour
                )
            )
        }
    } else {
        Column {
            AssistChip(
                onClick = {},
                label = { Text(if (diagnosticInfo.engine != "") diagnosticInfo.engine else "Disabled") },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = labelColour
                )
            )
            AssistChip(
                onClick = {},
                label = { Text("Detecting") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (diagnosticInfo.mode == AudioRouteOption.DETECT) CustomColours.GREEN else Color.Transparent,
                    labelColor = labelColour
                )
            )
            AssistChip(
                onClick = {},
                label = { Text("Streaming") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (diagnosticInfo.mode == AudioRouteOption.STREAM) CustomColours.GREEN else Color.Transparent,
                    labelColor = labelColour
                )
            )
        }
    }
}

@Preview(apiLevel = 35, widthDp = 800, heightDp = 200)
@Composable
fun DiagnosticBarPreview() {
    DiagnosticBar(
        modifier = Modifier.background(Color.White),
        diagnosticInfo = DiagnosticInfo(
            audioLevel = 50f,
            detectionLevel = 8.1f,
            detectionThreshold = 5f,
            vadDetection = true,
            motionDetected = true,
            hasCamera = true,
            lastMotionTimestamp = System.currentTimeMillis(),
            motionInterval = 10000,
            motionDetectionMode = "face"
        )
    )
}

@Preview(apiLevel = 35, widthDp = 380, heightDp = 500)
@Composable
fun DiagnosticBarPortraitPreview() {
    DiagnosticBar(
        modifier = Modifier.background(Color.White),
        diagnosticInfo = DiagnosticInfo(
            audioLevel = 30f,
            detectionLevel = 2.5f,
            detectionThreshold = 5f,
            motionDetected = false,
            hasCamera = true,
            lastMotionTimestamp = System.currentTimeMillis(),
            motionInterval = 10000,
            motionDetectionMode = "motion"
        )
    )
}
