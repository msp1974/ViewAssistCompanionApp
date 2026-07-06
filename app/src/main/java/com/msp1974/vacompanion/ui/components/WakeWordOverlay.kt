package com.msp1974.vacompanion.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun WakeWordOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF6EE7FF)
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(280))
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "wake_overlay")

        val pulse by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1500,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse"
        )

        val breathe by infiniteTransition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 900,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathe"
        )

        val barPhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (Math.PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 850,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "bar_phase"
        )

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.24f),
                            Color.Transparent
                        ),
                        radius = 900f
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(300.dp)
                    .graphicsLayer {
                        scaleX = breathe
                        scaleY = breathe
                    }
            ) {
                val center = this.center
                val maxRadius = size.minDimension / 2f

                repeat(3) { index ->
                    val localPulse = (pulse + index / 3f) % 1f
                    val radius = maxRadius * (0.22f + localPulse * 0.72f)
                    val alpha = (1f - localPulse) * 0.28f

                    drawCircle(
                        color = accentColor.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.55f),
                            accentColor.copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxRadius * 0.62f
                    ),
                    radius = maxRadius * 0.62f,
                    center = center
                )

                drawCircle(
                    color = Color.White.copy(alpha = 0.14f),
                    radius = maxRadius * 0.38f,
                    center = center
                )

                drawCircle(
                    color = accentColor.copy(alpha = 0.92f),
                    radius = maxRadius * 0.26f,
                    center = center
                )

                drawCircle(
                    color = Color.White.copy(alpha = 0.95f),
                    radius = maxRadius * 0.16f,
                    center = center
                )
            }

            Canvas(
                modifier = Modifier
                    .width(220.dp)
                    .height(52.dp)
                    .offset(y = 116.dp)
            ) {
                val bars = 11
                val gap = 7.dp.toPx()
                val barWidth = 7.dp.toPx()
                val totalWidth = bars * barWidth + (bars - 1) * gap
                val startX = (size.width - totalWidth) / 2f

                repeat(bars) { index ->
                    val wave = kotlin.math.sin(barPhase + index * 0.62f)
                    val normalized = ((wave + 1f) / 2f).coerceIn(0f, 1f)

                    val minHeight = 10.dp.toPx()
                    val maxHeight = 42.dp.toPx()
                    val height = minHeight + normalized * (maxHeight - minHeight)

                    val x = startX + index * (barWidth + gap)
                    val y = (size.height - height) / 2f

                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.42f + normalized * 0.48f),
                        topLeft = Offset(x, y),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(
                            x = barWidth / 2f,
                            y = barWidth / 2f
                        )
                    )
                }
            }
        }
    }
}