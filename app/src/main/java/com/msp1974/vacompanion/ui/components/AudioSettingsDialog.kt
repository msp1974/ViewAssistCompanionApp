package com.msp1974.vacompanion.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.msp1974.vacompanion.ui.theme.CustomColours

@Composable
fun AudioSettingsDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: (useBluetoothMic: Boolean) -> Unit,
    onClose: () -> Unit,
    initialValue: Boolean,
    confirmText: String = "OK",
    dismissText: String = "Cancel",
) {
    Dialog(
        onDismissRequest = { onDismissRequest() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        AudioSettingsDialogContent(
            initialValue = initialValue,
            onDismissRequest = onDismissRequest,
            onConfirmation = onConfirmation,
            onClose = onClose,
            confirmText = confirmText,
            dismissText = dismissText
        )
    }
}

@Composable
fun AudioSettingsDialogContent(
    initialValue: Boolean,
    onDismissRequest: () -> Unit,
    onConfirmation: (useBluetoothMic: Boolean) -> Unit,
    onClose: () -> Unit,
    confirmText: String,
    dismissText: String,
) {
    var useBluetoothMic by rememberSaveable { mutableStateOf(!initialValue) }

    Card(
        modifier = Modifier
            .padding(16.dp)
            .width(400.dp)
            .height(320.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Audio Settings",
                fontSize = 18.sp,
                modifier = Modifier
                    .padding(16.dp),
            )
            Text(
                text = "When using Bluetooth microphones, audio playback quality may degrade if audio output is also via Bluetooth.",
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Use Bluetooth Microphone",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .padding(end = 16.dp)
                )
                Switch(
                    checked = useBluetoothMic,
                    onCheckedChange = { useBluetoothMic = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CustomColours.GREEN,
                    )
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Bottom
            ) {
                Button(
                    onClick = {
                        onDismissRequest()
                        onClose()
                    },
                    modifier = Modifier.padding(8.dp),
                ) {
                    Text(dismissText)
                }
                Button(
                    onClick = {
                        onConfirmation(useBluetoothMic)
                        onClose()
                    },
                    modifier = Modifier.padding(8.dp),
                ) {
                    Text(confirmText)
                }
            }
        }
    }
}
