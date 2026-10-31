package com.msp1974.vacompanion.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.audio.WakeLevelCalibration
import com.msp1974.vacompanion.ui.VAViewModel

/**
 * Walks the user through setting this device's wake word level calibration: stand at a set
 * distance and say the wake word [WakeLevelCalibration.READINGS_NEEDED] times in a normal voice,
 * then save the offset that makes this device read the same as the others would. See
 * [WakeLevelCalibration].
 */
@Composable
fun WakeLevelCalibrationDialog(viewModel: VAViewModel, onDismiss: () -> Unit) {
    val config = viewModel.config
    val calibration = config.wakeLevelCalibration
    val state by calibration.state.collectAsState()
    val satelliteRunning = viewModel.vacaState.collectAsState().value.satelliteRunning
    val canListen = satelliteRunning && config.wakeWord != "none"

    // Never leave the device in calibration mode, where wake words are measured but not answered.
    DisposableEffect(Unit) { onDispose { calibration.cancel() } }

    val finished = state.readings.size >= WakeLevelCalibration.READINGS_NEEDED
    val summary = remember(state.readings, finished) {
        if (finished) WakeLevelCalibration.summarise(state.readings) else null
    }

    AlertDialog(
        onDismissRequest = { calibration.cancel(); onDismiss() },
        title = { Text("Calibrate wake word level") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    summary != null -> {
                        Text(
                            "This device read %.1f dB for your voice. Saving adds %+.1f dB, so it reads %.0f dB, the same as your other calibrated devices."
                                .format(summary.medianDb, summary.offsetDb, WakeLevelCalibration.REFERENCE_DBFS)
                        )
                        Text(
                            if (summary.consistent) {
                                "The readings were steady (within %.1f dB of each other).".format(summary.spreadDb)
                            } else {
                                "The readings varied by %.1f dB, which is a lot. Try again, keeping the same distance and speaking at an even volume.".format(summary.spreadDb)
                            },
                            color = if (summary.consistent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                        )
                    }
                    state.active -> {
                        val done = state.readings.size
                        Text("Say the wake word in your normal voice. Reading ${done + 1} of ${WakeLevelCalibration.READINGS_NEEDED}.")
                        LinearProgressIndicator(
                            progress = { done.toFloat() / WakeLevelCalibration.READINGS_NEEDED },
                            modifier = Modifier.fillMaxWidth()
                        )
                        state.readings.lastOrNull()?.let { Text("Last reading: %.1f dBFS".format(it)) }
                    }
                    else -> {
                        Text(
                            "Stand about an arm's length (1 m) in front of this device and say the wake word " +
                                "${WakeLevelCalibration.READINGS_NEEDED} times in your normal voice. The device will not respond while you do."
                        )
                        Text("Do the same on each of your devices - from the same distance, at the same volume - so their levels can be compared.")
                        Text("Current calibration: %+.1f dB".format(config.wakeLevelOffsetDb))
                        if (!canListen) {
                            Text(
                                "Wake word detection isn't running, so there is nothing to measure. Connect to Home Assistant and choose a wake word first.",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                summary != null -> TextButton(onClick = {
                    config.wakeLevelOffsetDb = summary.offsetDb
                    calibration.cancel()
                    onDismiss()
                }) { Text("Save") }
                state.active -> TextButton(onClick = {}, enabled = false) { Text("Listening...") }
                else -> TextButton(onClick = { calibration.start() }, enabled = canListen) { Text("Start") }
            }
        },
        dismissButton = {
            when {
                summary != null -> TextButton(onClick = { calibration.start() }) { Text("Redo") }
                state.active -> TextButton(onClick = { calibration.cancel() }) { Text("Cancel") }
                else -> Column {
                    if (config.wakeLevelOffsetDb != 0f) {
                        TextButton(onClick = { config.wakeLevelOffsetDb = 0f }) { Text("Reset to 0 dB") }
                    }
                    TextButton(onClick = { calibration.cancel(); onDismiss() }) { Text("Close") }
                }
            }
        },
    )
}
