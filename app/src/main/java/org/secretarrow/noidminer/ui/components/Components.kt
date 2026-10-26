package org.secretarrow.noidminer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.secretarrow.noidminer.ui.theme.AuroraAmber
import org.secretarrow.noidminer.ui.theme.AuroraCyan
import org.secretarrow.noidminer.ui.theme.AuroraMint
import org.secretarrow.noidminer.ui.theme.AuroraRose
import org.secretarrow.noidminer.ui.theme.GradientHero

/**
 * Glass-morphism card — semi-transparent surface with subtle gradient border.
 * The signature component of the v2 redesign — used for hero, stats, settings.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.verticalGradient(
                    listOf(
                        bg.copy(alpha = 0.85f),
                        bg.copy(alpha = 0.65f),
                    ),
                ),
            ).border(
                BorderStroke(1.dp, borderColor.copy(alpha = 0.6f)),
                RoundedCornerShape(cornerRadius),
            ),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

/**
 * Primary CTA button — animated gradient fill, scales on press.
 * Used for Start/Resume/Pause/Stop on the Mine screen.
 */
@Composable
fun GradientButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color> = GradientHero,
    contentColor: Color = Color.White,
    height: Dp = 84.dp,
) {
    val infinite = rememberInfiniteTransition(label = "shine")
    val shift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shineShift",
    )
    Box(
        modifier = modifier
            .height(height)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    colors = gradient,
                    start = Offset(shift * -800f, 0f),
                    end = Offset(shift * 800f + 800f, 800f),
                ),
            ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}

/**
 * Compact status pill — colored dot + label. Used for backend / GPU / thermal.
 */
@Composable
fun StatusPill(
    label: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
    icon: ImageVector? = null,
) {
    val (dot, fg) = when (tone) {
        PillTone.Success -> AuroraMint to MaterialTheme.colorScheme.tertiary
        PillTone.Warning -> AuroraAmber to MaterialTheme.colorScheme.secondary
        PillTone.Error -> AuroraRose to MaterialTheme.colorScheme.error
        PillTone.Info -> AuroraCyan to MaterialTheme.colorScheme.secondary
        PillTone.Neutral ->
            MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot),
        )
        icon?.let {
            Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

enum class PillTone { Success, Warning, Error, Info, Neutral }

/**
 * Compact metric tile — small label, big number, optional hint.
 * Same shape as v1 but with a refined glass surface.
 */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface,
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Section header with optional trailing content.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke()
    }
}

/**
 * Animated live indicator — pulsing dot + label.
 * Used in the hero card to signal "mining right now".
 */
@Composable
fun LiveIndicator(active: Boolean, label: String, modifier: Modifier = Modifier) {
    val pulseAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.3f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(AuroraMint.copy(alpha = pulseAlpha))
                .drawBehind {
                    drawCircle(
                        AuroraMint.copy(alpha = pulseAlpha * 0.3f),
                        radius = size.minDimension * 0.9f,
                    )
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Simple fade-in / fade-out wrapper for empty / error states.
 */
@Composable
fun FadeBlock(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(120)),
        modifier = modifier,
        content = { content() },
    )
}
