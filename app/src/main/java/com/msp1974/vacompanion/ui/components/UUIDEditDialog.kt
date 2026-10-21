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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.then
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun UUIDEditDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: (uuid: String) -> Unit,
    onClose: () -> Unit,
    initText: String,
    confirmText: String = "OK",
    dismissText: String? = "Cancel",
    title: String = "Edit Device UUID",
    message: String = UUID_CHANGE_WARNING,
    dismissible: Boolean = true,
) {
    val uuid = rememberTextFieldState(initialText = initText)
    Dialog(
        onDismissRequest = { onDismissRequest() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        UUIDEditDialogContent(
            uuid = uuid,
            onDismissRequest = onDismissRequest,
            onConfirmation = onConfirmation,
            onClose = onClose,
            confirmText = confirmText,
            dismissText = dismissText,
            title = title,
            message = message,
        )
    }
}

private const val UUID_CHANGE_WARNING =
    "WARNING: Changing the UUID will unpair the device and require the VACA and View Assist entries to be deleted and readded."

@Composable
fun UUIDEditDialogContent(
    uuid: TextFieldState,
    onDismissRequest: () -> Unit,
    onConfirmation: (uuid: String) -> Unit,
    onClose: () -> Unit,
    confirmText: String,
    dismissText: String?,
    title: String = "Edit Device UUID",
    message: String = UUID_CHANGE_WARNING,
) {
    // Draw a rectangle shape with rounded corners inside the dialog
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
                text = title,
                fontSize = 18.sp,
                modifier = Modifier
                    .padding(16.dp),
            )
            Text(
                text = message,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(16.dp),
            )
            TextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                state = uuid,
                label = { Text("UUID") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                inputTransformation = InputTransformation.maxLength(32)
                    .then(CustomInputTransformation()),
            )
            Row(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Bottom
            ) {
                if (dismissText != null) {
                    Button(
                        onClick = {
                            onDismissRequest()
                            onClose()
                                  },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(dismissText)
                    }
                }
                Button(
                    onClick = {
                        onConfirmation(uuid.text.toString())
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

class CustomInputTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        // Iterate through all characters in the buffer backwards to safely delete
        for (i in length - 1 downTo 0) {
            val char = asCharSequence()[i]
            if (!char.isLetterOrDigit() && char != '_' && char != '-') {
                // If the character is not alphanumeric and not _ or -, delete it
                delete(i, i + 1)
            }
        }
    }
}

@Preview
@Composable
fun UUIDEditDialogPreview() {
    val uuid = rememberTextFieldState(initialText = "abcde123456")
    // Preview the content directly to avoid Dialog-related render issues in Preview
    UUIDEditDialogContent(
        uuid = uuid,
        onDismissRequest = {},
        onConfirmation = {},
        onClose = {},
        confirmText = "OK",
        dismissText = "Cancel"
    )
}
