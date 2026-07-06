package com.msp1974.vacompanion.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.ui.theme.CustomColours
import com.msp1974.vacompanion.utils.Helpers.Companion.round


@Composable
fun InfoGauge(
    canvasSize: Dp = 150.dp,
    indicatorValue: Float = 0.0f,
    disabled: Boolean = false,
    disabledText: String = "",
    maxIndicatorValue: Int = 100,
    decimalPlaces: Int = 0,
    backgroundIndicatorColor: Color = Color.Gray.copy(alpha = 0.3f),
    indicatorStrokeWidth: Float = 20f,
    foregroundIndicatorColor: Color = CustomColours.GREEN,
    //    indicatorStrokeCap: StrokeCap = StrokeCap.Round,
    bigTextFontSize: TextUnit = MaterialTheme.typography.headlineMedium.fontSize,
    bigTextColor: Color =  Color.White,
    bigTextSuffix: String = "",
    smallText: String = "Level",
    smallTextFontSize: TextUnit = MaterialTheme.typography.bodyLarge.fontSize,
    smallTextColor: Color = Color.White
) {
    var allowedIndicatorValue by remember {
        mutableFloatStateOf(maxIndicatorValue.toFloat())
    }
    allowedIndicatorValue = if (indicatorValue <= maxIndicatorValue) {
        indicatorValue
    } else {
        maxIndicatorValue.toFloat()
    }

    var animatedIndicatorValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(key1 = allowedIndicatorValue) {
        animatedIndicatorValue = allowedIndicatorValue
    }

    val percentage =
        (animatedIndicatorValue / maxIndicatorValue) * 100

    val sweepAngle by animateFloatAsState(
        targetValue = (2.4 * percentage).toFloat(),
        animationSpec = tween(300)
    )

    val receivedValue by animateFloatAsState(
        targetValue = allowedIndicatorValue,
        animationSpec = tween(300)
    )

    Column(
        modifier = Modifier
            .width(canvasSize)
            .height(canvasSize / 1.2f)
            .drawBehind {
                val componentSize = size / 1.2f
                backgroundIndicator(
                    componentSize = componentSize,
                    indicatorColor = backgroundIndicatorColor,
                    indicatorStrokeWidth = indicatorStrokeWidth,
//                    indicatorStokeCap = indicatorStrokeCap
                )
                foregroundIndicator(
                    sweepAngle = sweepAngle,
                    componentSize = componentSize,
                    indicatorColor = foregroundIndicatorColor,
                    indicatorStrokeWidth = indicatorStrokeWidth,
//                    indicatorStokeCap = indicatorStrokeCap
                )
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EmbeddedElements(
            bigText = "%.${decimalPlaces}f".format(receivedValue),
            bigTextFontSize = bigTextFontSize,
            bigTextColor = bigTextColor,
            bigTextSuffix = bigTextSuffix,
            smallText = smallText,
            smallTextColor = smallTextColor,
            smallTextFontSize = smallTextFontSize,
            disabled = disabled,
            disabledText = disabledText
        )
    }
}

fun DrawScope.backgroundIndicator(
    componentSize: Size,
    indicatorColor: Color,
    indicatorStrokeWidth: Float,
//    indicatorStokeCap: StrokeCap
) {
    drawArc(
        size = Size(componentSize.width, componentSize.height * 1.2f),
        color = indicatorColor,
        startAngle = 150f,
        sweepAngle = 240f,
        useCenter = false,
        style = Stroke(
            width = indicatorStrokeWidth,
            cap = StrokeCap.Round
        ),
        topLeft = Offset(
            x = (size.width - componentSize.width) / 2f,
            y = (size.height - componentSize.height) / 2f
        )
    )
}

fun DrawScope.foregroundIndicator(
    sweepAngle: Float,
    componentSize: Size,
    indicatorColor: Color,
    indicatorStrokeWidth: Float,
//    indicatorStokeCap: StrokeCap
) {
    drawArc(
        size = Size(componentSize.width, componentSize.height * 1.2f),
        color = indicatorColor,
        startAngle = 150f,
        sweepAngle = sweepAngle,
        useCenter = false,
        style = Stroke(
            width = indicatorStrokeWidth,
            cap = StrokeCap.Round
        ),
        topLeft = Offset(
            x = (size.width - componentSize.width) / 2f,
            y = (size.height - componentSize.height) / 2f
        )
    )
}

@Composable
fun EmbeddedElements(
    bigText: String,
    bigTextFontSize: TextUnit,
    bigTextColor: Color,
    bigTextSuffix: String,
    smallText: String,
    smallTextColor: Color,
    smallTextFontSize: TextUnit,
    disabled: Boolean,
    disabledText: String
) {
    Text(
        modifier = Modifier.padding(top = 20.dp),
        text = smallText,
        color = smallTextColor,
        fontSize = smallTextFontSize,
        textAlign = TextAlign.Center
    )
    if (disabled) {
        Text(
            text = disabledText,
            color = bigTextColor,
            fontSize = smallTextFontSize,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
    } else {
        Text(
            text = if (bigTextSuffix.isEmpty()) bigText else "$bigText ${bigTextSuffix.take(2)}",
            color = bigTextColor,
            fontSize = bigTextFontSize,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
@Preview(showBackground = true)
fun InfoGaugePreview() {
    InfoGauge(
        indicatorValue = 5.8f,
        decimalPlaces = 1,
        maxIndicatorValue = 10,
        smallText = "Detection",
        disabledText = "Muted",
        disabled = false
    )
}