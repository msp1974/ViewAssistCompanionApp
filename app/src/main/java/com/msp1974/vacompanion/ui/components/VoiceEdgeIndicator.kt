package com.msp1974.vacompanion.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.ui.VoiceIndicatorPhase
import com.msp1974.vacompanion.ui.VoiceIndicatorState

@Composable
fun VoiceEdgeIndicator(
    state: VoiceIndicatorState,
    modifier: Modifier = Modifier,
) {
    if (!state.show || state.phase == VoiceIndicatorPhase.NONE) {
        return
    }

    val pulseTransition = rememberInfiniteTransition(label = "voiceIndicatorPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voiceIndicatorPulseAlpha"
    )

    val audioLevel = state.audioLevel.coerceIn(0f, 1f)
    val (borderColor, borderWidth) = when (state.phase) {
        VoiceIndicatorPhase.LISTENING -> {
            Color(0xFF3DA5FF).copy(alpha = 0.55f + (audioLevel * 0.40f)) to (8 + (audioLevel * 10)).dp
        }
        VoiceIndicatorPhase.THINKING -> {
            Color(0xFF54C7FF).copy(alpha = 0.35f + pulseAlpha * 0.5f) to 8.dp
        }
        VoiceIndicatorPhase.SPEAKING -> {
            Color(0xFF7DDCFF).copy(alpha = 0.65f) to 6.dp
        }
        VoiceIndicatorPhase.NONE -> {
            Color.Transparent to 0.dp
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .border(borderWidth, borderColor)
    )
}
