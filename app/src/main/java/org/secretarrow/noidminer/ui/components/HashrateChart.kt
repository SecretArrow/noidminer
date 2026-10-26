package org.secretarrow.noidminer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.max

/**
 * Live hashrate sparkline — samples [value] into a rolling window and draws
 * a smooth gradient area chart. Pure Canvas (no chart dependency), so it
 * stays tiny and fast.
 */
@Composable
fun HashrateChart(
    value: Double,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7C5CFF),
    accent2: Color = Color(0xFF22D3EE),
    samples: Int = 60,
) {
    val history = remember { mutableStateListOf(0.0) }
    var smooth by remember { mutableStateOf(0.0) }
    val animated by animateFloatAsState(
        targetValue = smooth.toFloat(),
        animationSpec = tween(450, easing = LinearEasing),
        label = "hashrate",
    )
    LaunchedEffect(value) {
        // Exponential smoothing keeps the hero number readable.
        smooth = if (smooth == 0.0) value else smooth * 0.7 + value * 0.3
        history.add(value)
        while (history.size > samples) history.removeAt(0)
    }
    Box(modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (history.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            val maxV = max(history.max(), 1e-9)
            val stepX = w / (samples - 1)
            val startX = w - (history.size - 1) * stepX

            val points = history.mapIndexed { i, v ->
                Offset(startX + i * stepX, h - (v / maxV).toFloat() * h * 0.92f - h * 0.04f)
            }

            val line = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    val p0 = points[i - 1]
                    val p1 = points[i]
                    val midX = (p0.x + p1.x) / 2f
                    cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(points.last().x, h)
                lineTo(points.first().x, h)
                close()
            }

            val brush = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.9f), accent2))
            clipPath(area) {
                drawRect(brush = brush, alpha = 0.16f)
            }
            drawPath(
                path = line,
                brush = brush,
                style = Stroke(width = 3.5f, cap = StrokeCap.Round),
            )
            val last = points.last()
            drawCircle(
                brush = brush,
                radius = 5f,
                center = Offset(last.x, last.y * 0f + last.y),
            )
            // Animated pulse dot scales with the smoothed value.
            if (animated > 0f) {
                drawCircle(
                    color = accent2.copy(alpha = 0.35f),
                    radius = 9f,
                    center = last,
                )
            }
        }
    }
}
