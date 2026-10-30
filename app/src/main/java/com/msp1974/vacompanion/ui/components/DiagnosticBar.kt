package com.msp1974.vacompanion.ui.components

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
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
import com.msp1974.vacompanion.utils.Event
import java.lang.System

/** Mic level gauge range, up to 0 dBFS (full scale). */
private const val MIC_LEVEL_GAUGE_MIN_DBFS = -38f
private const val MIC_LEVEL_GAUGE_MAX_DBFS = 0f


@SuppressLint("DefaultLocale")
@Composable
fun DiagnosticBar(
    diagnosticInfo: DiagnosticInfo,
    onEvent: (Event) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    var expanded by remember { mutableStateOf(false) }
    val labelColour = Color.White
    val micLevel0to100 = ((diagnosticInfo.audioLevel - MIC_LEVEL_GAUGE_MIN_DBFS) / (MIC_LEVEL_GAUGE_MAX_DBFS - MIC_LEVEL_GAUGE_MIN_DBFS) * 100f).coerceIn(0f, 100f)

    Column(
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
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        InfoGauge(
                            canvasSize = 130.dp,
                            indicatorValue = micLevel0to100,
                            minIndicatorValue = 0f,
                            maxIndicatorValue = 100f,
                            decimalPlaces = 0,
                            bigTextFontSize = MaterialTheme.typography.headlineSmall.fontSize,
                            smallText = "Mic",
                            foregroundIndicatorColor = CustomColours.GREEN,
                            disabledText = "Muted",
                            disabled = diagnosticInfo.muted
                        )
                        MicrophoneChip(diagnosticInfo.activeMic)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        InfoGauge(
                            canvasSize = 130.dp,
                            indicatorValue = diagnosticInfo.detectionLevel,
                            maxIndicatorValue = 10f,
                            decimalPlaces = 1,
                            smallText = "Wake",
                            foregroundIndicatorColor = if (diagnosticInfo.detectionLevel >= diagnosticInfo.detectionThreshold) CustomColours.GREEN else CustomColours.AMBER,
                            disabledText = "Off",
                            disabled = diagnosticInfo.wakeWord == "none"
                        )
                        WakeWordChip(diagnosticInfo.wakeWord)
                    }
                    if (diagnosticInfo.hasCamera && diagnosticInfo.motionDetectionMode != "none") {
                        MotionIndicator(diagnosticInfo)
                    }
                }
                DiagnosticChips(diagnosticInfo, onEvent)
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        InfoGauge(
                            indicatorValue = micLevel0to100,
                            minIndicatorValue = 0f,
                            maxIndicatorValue = 100f,
                            decimalPlaces = 0,
                            bigTextFontSize = MaterialTheme.typography.headlineSmall.fontSize,
                            smallText = "Mic Level",
                            foregroundIndicatorColor = CustomColours.GREEN,
                            disabledText = "Muted",
                            disabled = diagnosticInfo.muted
                        )
                        MicrophoneChip(diagnosticInfo.activeMic)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        InfoGauge(
                            indicatorValue = diagnosticInfo.detectionLevel,
                            maxIndicatorValue = 10f,
                            decimalPlaces = 1,
                            smallText = "Detection",
                            foregroundIndicatorColor = if (diagnosticInfo.detectionLevel >= diagnosticInfo.detectionThreshold) CustomColours.GREEN else CustomColours.AMBER,
                            disabledText = "Disabled",
                            disabled = diagnosticInfo.wakeWord == "none"
                        )
                        WakeWordChip(diagnosticInfo.wakeWord)
                    }
                }

                Column(
                    modifier = Modifier.padding(start = 10.dp)
                ) {
                    DiagnosticChips(diagnosticInfo, onEvent)
                }
            }
        }

        if (diagnosticInfo.audioLog.isNotEmpty()) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Audio Log",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                ) {
                    diagnosticInfo.audioLog.toSortedMap(compareByDescending { it }).forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Column 1: Timestamp (Top Aligned)
                            Text(
                                text = entry.value.timestamp,
                                style = MaterialTheme.typography.bodyLarge,
                                color = labelColour.copy(alpha = 0.5f),
                                modifier = Modifier.width(80.dp)
                            )

                            // Column 2: Request and Response (Left Aligned)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (entry.value.wakeWord.isNotEmpty()) {
                                    Text(
                                        text = "${entry.value.wakeWord} (${entry.value.maxScore})",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = labelColour
                                    )
                                }
                                if (entry.value.request.isNotEmpty()) {
                                    Text(
                                        text = entry.value.request,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = labelColour
                                    )
                                }
                                if (entry.value.response.isNotEmpty()) {
                                    Text(
                                        text = entry.value.response,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = labelColour.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                            if (entry.value.audioFilePath != null) {
                                IconButton(
                                    onClick = {
                                        onEvent(
                                            Event(
                                                "playRecording", "",
                                                entry.key
                                            )
                                        )
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (entry.value.isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = if (entry.value.isPlaying) "Stop" else "Play",
                                        tint = if (entry.value.isPlaying) CustomColours.AMBER else CustomColours.GREEN
                                    )
                                }
                            }
                        }
                        if (entry.key != diagnosticInfo.audioLog.keys.first()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = Color.White.copy(alpha = 0.05f)
                            )
                        }
                    }
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
private fun MicrophoneChip(activeMic: String) {
    if (activeMic.isNotEmpty() && activeMic != "None") {
        AssistChip(
            onClick = {},
            label = {
                Text(
                    text = activeMic,
                    style = MaterialTheme.typography.labelMedium
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = Color.White,
            )
        )
    }
}

@Composable
private fun WakeWordChip(wakeWord: String) {
    if (wakeWord.isNotEmpty() && wakeWord != "none") {
        AssistChip(
            onClick = {},
            label = {
                Text(
                    text = wakeWord,
                    style = MaterialTheme.typography.labelMedium
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = Color.White,
            )
        )
    }
}

@Composable
private fun DiagnosticChips(diagnosticInfo: DiagnosticInfo, onEvent: (com.msp1974.vacompanion.utils.Event) -> Unit = {}) {
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
            AssistChip(
                onClick = {
                    onEvent(com.msp1974.vacompanion.utils.Event("toggleRecording", "", ""))
                },
                label = { Text("Record Audio") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (diagnosticInfo.recordingWakewordEnabled) CustomColours.AMBER else Color.Transparent,
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
            AssistChip(
                onClick = {
                    onEvent(com.msp1974.vacompanion.utils.Event("toggleRecording", "", ""))
                },
                label = { Text("Record Audio") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (diagnosticInfo.recordingWakewordEnabled) CustomColours.AMBER else Color.Transparent,
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
            audioLevel = -18f,
            detectionLevel = 8.1f,
            detectionThreshold = 5f,
            vadDetection = true,
            motionDetected = true,
            hasCamera = true,
            lastMotionTimestamp = System.currentTimeMillis(),
            motionInterval = 10000,
            motionDetectionMode = "face",
            activeMic = "Built-in Mic (Built-in Mic)",
            wakeWord = "hey_assistant",
            audioLog = mutableMapOf(1L to
                com.msp1974.vacompanion.satellite.AudioLogEntry(
                    timestamp = "12:00:00",
                    wakeWord = "hey_assistant",
                    maxScore = 8.1f,
                    request = "Turn on the living room lights",
                    response = "Okay, turning on the living room lights"
                )
            )
        )
    )
}

@Preview(apiLevel = 35, widthDp = 380, heightDp = 500)
@Composable
fun DiagnosticBarPortraitPreview() {
    DiagnosticBar(
        modifier = Modifier.background(Color.White),
        diagnosticInfo = DiagnosticInfo(
            audioLevel = -24f,
            detectionLevel = 2.5f,
            detectionThreshold = 5f,
            motionDetected = false,
            hasCamera = true,
            lastMotionTimestamp = System.currentTimeMillis(),
            motionInterval = 10000,
            motionDetectionMode = "motion",
            activeMic = "Built-in Mic (Built-in Mic)",
            wakeWord = "hey_assistant",
            audioLog = mutableMapOf(1L to
                    com.msp1974.vacompanion.satellite.AudioLogEntry(
                        timestamp = "12:00:00",
                        wakeWord = "hey_assistant",
                        maxScore = 8.1f,
                        request = "Turn on the living room lights",
                        response = "Okay, turning on the living room lights"
                    )
            )
        )
    )
}
