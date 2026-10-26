package org.secretarrow.noidminer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import org.secretarrow.noidminer.ui.theme.AuroraCyan
import org.secretarrow.noidminer.ui.theme.AuroraMint
import org.secretarrow.noidminer.ui.theme.AuroraRose
import org.secretarrow.noidminer.ui.theme.AuroraViolet

/**
 * Radial gauge — circular progress arc that visualizes hashrate as a fraction
 * of [maxValue]. Three color zones (green / cyan / violet) make it instantly
 * readable: idle (0%), ramping (≤30%), nominal (≤70%), peaking (>70%).
 *
 * The arc sweeps 270° starting from the bottom-left, leaving a 45° gap at the
 * bottom for the numeric readout below.
 *
 * Used as the hero element of the redesigned Mine screen.
 */
@Composable
fun RadialGauge(
    value: Float,
    maxValue: Float,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    strokeWidth: Dp = 14.dp,
) {
    val fraction = (value / maxValue.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(700, easing = LinearEasing),
        label = "gaugeFraction",
    )

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val arcBrush = Brush.sweepGradient(
        listOf(AuroraMint, AuroraCyan, AuroraViolet, AuroraRose, AuroraMint),
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
            )
            val diameter = size.toPx() - strokeWidth.toPx()
            val topLeft = Offset(
                x = (this.size.width - diameter) / 2f,
                y = (this.size.height - diameter) / 2f,
            )
            val arcSize = Size(diameter, diameter)

            // Background track — full 270°.
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )

            // Foreground progress — fraction of 270°.
            drawArc(
                brush = arcBrush,
                startAngle = 135f,
                sweepAngle = 270f * animatedFraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
    }
}
