package com.msp1974.vacompanion.ui.components

import android.annotation.SuppressLint
import android.graphics.Color.red
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.MobiledataOff
import androidx.compose.material.icons.filled.PermScanWifi
import androidx.compose.material.icons.filled.SignalWifiStatusbarNull
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

@SuppressLint("DefaultLocale")
@Composable
fun IconStatusBlock(
    message: String,
    icon: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // Deliberately NOT fillMaxWidth(): this sits in a Box on top of the WebView, and a full-width
    // row - even an invisible one - claims Compose's hit-testing for that whole strip (because of
    // the pointerInput modifier below), which swallowed touches meant for the WebView underneath,
    // including the two-finger swipe that opens the quick actions sheet. Wrapping to just the
    // icon's own bounds keeps the touch-blocking scoped to the icon itself.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = modifier
            .zIndex(2f)
            .background(Color.Transparent)
            .pointerInput(onClick) {
                // Taps are consumed here (so they don't reach the WebView) and passed on to
                // onClick when the icon is interactive.
                detectTapGestures(onTap = { onClick?.invoke() })
            }
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp)
        ) {
            Icon(
                imageVector = getIcon(icon),
                contentDescription = icon,
                tint = Color.White,
                modifier = Modifier
                    .size(90.dp)
                    .padding(20.dp)
                    .drawBehind {
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.8f),
                            radius = this.size.maxDimension
                        )
                    },
            )
        }
    }
}

private fun getIcon(name: String): ImageVector {
    return when (name) {
        "nowifi" -> Icons.Filled.WifiOff
        "noserver" -> Icons.Filled.MobiledataOff
        "camera" -> Icons.Filled.Videocam
        else -> Icons.AutoMirrored.Filled.Note
    }
}

@Preview(apiLevel = 35)
@Composable
fun MessageBlockPreview() {
    IconStatusBlock(
        modifier = Modifier.background(Color.White),
        message="Preview Test Message",
        icon="nowifi"
    )

}