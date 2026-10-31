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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.audio.WakeLevelCalibration
import com.msp1974.vacompanion.audio.WakeLevelCalibration.Phase
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.layouts.config.friendly

/**
 * Walks the user through calibrating the microphone: say the wake word a few times in a normal voice.
 * That sets how much to add to this device's wake word level so it can be compared with other
 * devices'. See [WakeLevelCalibration].
 */
@Composable
fun MicrophoneCalibrationDialog(viewModel: VAViewModel, onDismiss: () -> Unit) {
    val config = viewModel.config
    val calibration = config.wakeLevelCalibration
    val state by calibration.state.collectAsState()
    val satelliteRunning = viewModel.vacaState.collectAsState().value.satelliteRunning
    val canListen = satelliteRunning && config.wakeWord != "none"
    val wakeWord = "“${friendly(config.wakeWord)}”"
    val needed = WakeLevelCalibration.READINGS_NEEDED

    // Never leave the device in calibration mode, where wake words are measured but not answered.
    DisposableEffect(Unit) { onDispose { calibration.cancel() } }

    val summary = if (state.phase == Phase.DONE) calibration.summary() else null

    AlertDialog(
        onDismissRequest = { calibration.cancel(); onDismiss() },
        title = { Text("Microphone calibration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.phase) {
                    Phase.LISTENING -> {
                        Text("Say $wakeWord in your normal voice. Reading ${state.readings.size + 1} of $needed.")
                        LinearProgressIndicator(
                            progress = { state.readings.size.toFloat() / needed },
                            modifier = Modifier.fillMaxWidth()
                        )
                        state.readings.lastOrNull()?.let { Text("Last reading: %.1f dBFS".format(it)) }
                    }
                    Phase.DONE -> if (summary != null) {
                        Text(
                            "This device read %.1f dB for your normal voice. Saving adds %+.1f dB, so it reads %.0f dB, the same as your other calibrated devices."
                                .format(summary.medianDb, summary.offsetDb, WakeLevelCalibration.REFERENCE_DBFS)
                        )
                        Text(
                            if (summary.consistent) {
                                "Your voice was steady (within %.1f dB).".format(summary.spreadDb)
                            } else {
                                "Your voice varied by %.1f dB, which is a lot. Try again, keeping the same distance and speaking at an even volume.".format(summary.spreadDb)
                            },
                            color = if (summary.consistent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                        )
                    }
                    Phase.IDLE -> {
                        Text(
                            "This sets how loud this device hears you, so that devices can be compared when several hear you."
                        )
                        Text(
                            "Stand about an arm's length (1 m) from the device and say $wakeWord $needed times in your normal voice. " +
                                "The device will not respond while you do."
                        )
                        Text("Do the same on each of your devices - from the same distance, at the same volume - so their levels can be compared.")
                        Text(
                            if (config.wakeLevelCalibrated) {
                                "Now: calibrated at %+.1f dB.".format(config.wakeLevelOffsetDb)
                            } else {
                                "Now: not calibrated."
                            }
                        )
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
            when (state.phase) {
                Phase.DONE -> TextButton(onClick = {
                    summary?.let {
                        config.wakeLevelOffsetDb = it.offsetDb
                        config.wakeLevelCalibrated = true
                    }
                    calibration.cancel()
                    onDismiss()
                }) { Text("Save") }
                Phase.LISTENING -> TextButton(onClick = {}, enabled = false) { Text("Listening...") }
                Phase.IDLE -> TextButton(onClick = { calibration.start() }, enabled = canListen) { Text("Start") }
            }
        },
        dismissButton = {
            when (state.phase) {
                Phase.DONE -> TextButton(onClick = { calibration.start() }) { Text("Redo") }
                Phase.LISTENING -> TextButton(onClick = { calibration.cancel() }) { Text("Cancel") }
                Phase.IDLE -> Column {
                    if (config.wakeLevelCalibrated) {
                        TextButton(onClick = {
                            config.wakeLevelOffsetDb = 0f
                            config.wakeLevelCalibrated = false
                        }) { Text("Reset") }
                    }
                    TextButton(onClick = { calibration.cancel(); onDismiss() }) { Text("Close") }
                }
            }
        },
    )
}
