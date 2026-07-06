package com.msp1974.vacompanion.ui.components

import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.ui.theme.CustomColours

@Composable
fun LabelledSwitch(isOn: Boolean, callback: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(isOn) }
    Row(
    verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Launch on boot",
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Start,
            fontSize = 18.sp,
            modifier = Modifier
                .padding(end = 16.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                callback(it)
            },
            thumbContent = if (checked) {
                {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                }
            } else {
                null
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = CustomColours.GREEN,
                checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                uncheckedTrackColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        )
    }
}