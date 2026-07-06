package com.msp1974.vacompanion.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign

@Composable
fun InfoItem(label: String, value: String) {
    Row {
        TableCell(text = label, weight = 0.4f)
        TableCell(text = value, weight = 0.6f, alignment = TextAlign.End)
    }
}