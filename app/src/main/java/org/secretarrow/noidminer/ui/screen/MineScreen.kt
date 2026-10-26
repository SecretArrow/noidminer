package org.secretarrow.noidminer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.secretarrow.noidminer.ui.components.GlassCard
import org.secretarrow.noidminer.ui.components.GradientButton
import org.secretarrow.noidminer.ui.components.HashrateChart
import org.secretarrow.noidminer.ui.components.LiveIndicator
import org.secretarrow.noidminer.ui.components.PillTone
import org.secretarrow.noidminer.ui.components.RadialGauge
import org.secretarrow.noidminer.ui.components.StatCard
import org.secretarrow.noidminer.ui.components.StatusPill
import org.secretarrow.noidminer.ui.theme.GradientDanger
import org.secretarrow.noidminer.ui.theme.GradientHero
import org.secretarrow.noidminer.ui.theme.GradientIdle
import org.secretarrow.noidminer.ui.theme.GradientMining
import org.secretarrow.noidminer.util.Format
import org.secretarrow.noidminer.viewmodel.MineViewModel

/**
 * Mine v2 — the hero screen.
 *
 * Layout (top → bottom):
 *  1. Status strip — LiveIndicator (pulsing dot) + backend / GPU / thermal pills
 *  2. Hero glass card — RadialGauge center + big hashrate number + sparkline strip
 *  3. Three stat cards — Accepted / Rejected / Uptime (compact glass tiles)
 *  4. Primary CTA — GradientButton (Start / Pause / Resume)
 *  5. Stop button — subtle text below CTA (only visible when running)
 *
 * Hero number uses displayMedium (44sp Black). Gauge max is 2x the current
 * hashrate so the arc never saturates at idle and always shows movement.
 */
@Composable
fun MineScreen(
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MineViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val thermal by viewModel.thermal.collectAsState()

    val isRunning = state.running
    val isPaused = state.paused

    // CTA configuration — flips between three states with distinct gradients.
    val (ctaLabel, ctaIcon, ctaAction, ctaGradient) = when {
        !isRunning -> Cta("Start mining", Icons.Filled.PlayArrow, onStart, GradientHero)
        isPaused -> Cta("Resume", Icons.Filled.PlayArrow, onResume, GradientMining)
        else -> Cta("Pause", Icons.Filled.Pause, onPause, GradientIdle)
    }

    // Gauge max — adaptive so the arc shows movement but never saturates.
    val gaugeMax = (state.hashrateHps * 2.0).coerceAtLeast(50.0).toFloat()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---------------------------------------------------- status strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveIndicator(active = isRunning && !isPaused, label = if (isRunning) "LIVE" else "IDLE")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    label = state.backend.ifBlank { "—" },
                    tone = PillTone.Info,
                    icon = Icons.Filled.Memory,
                )
                StatusPill(
                    label = if (state.gpuAvailable) "GPU" else "CPU",
                    tone = if (state.gpuAvailable) PillTone.Success else PillTone.Neutral,
                )
                StatusPill(
                    label = thermal,
                    tone = when {
                        thermal in listOf("severe", "critical", "emergency", "shutdown") -> PillTone.Error
                        thermal in listOf("moderate", "light") -> PillTone.Warning
                        else -> PillTone.Success
                    },
                    icon = Icons.Filled.Thermostat,
                )
            }
        }

        // ---------------------------------------------------- hero glass card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 32.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp,
                vertical = 24.dp,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                RadialGauge(
                    value = state.hashrateHps.toFloat(),
                    maxValue = gaugeMax,
                    size = 220.dp,
                    strokeWidth = 14.dp,
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "HASHRATE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = Format.hashrate(state.hashrateHps),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = when {
                            !isRunning -> "tap start to begin"
                            isPaused -> "paused · ${state.pausedReason ?: ""}"
                            else -> "h=${state.currentHeight} · ${state.backend}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Sparkline strip — last 60 samples, gradient fill.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                HashrateChart(
                    value = state.hashrateHps,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                )
            }
        }

        // ---------------------------------------------------- stat grid
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "Accepted",
                value = Format.compact(state.sharesAccepted),
                modifier = Modifier.weight(1f),
                hint = "shares",
            )
            StatCard(
                label = "Rejected",
                value = Format.compact(state.sharesRejected),
                modifier = Modifier.weight(1f),
                hint = "shares",
            )
            StatCard(
                label = "Uptime",
                value = if (isRunning) Format.duration(System.currentTimeMillis() - state.startedAtMs) else "—",
                modifier = Modifier.weight(1f),
                hint = "${Format.compact(state.hashesTried)} hashes",
            )
        }

        // ---------------------------------------------------- primary CTA
        GradientButton(
            label = ctaLabel,
            icon = ctaIcon,
            onClick = ctaAction,
            gradient = ctaGradient,
            contentColor = if (isRunning && !isPaused) MaterialTheme.colorScheme.onSurface else Color.White,
        )

        // ---------------------------------------------------- stop button
        if (isRunning) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onStop)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "Stop mining",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(96.dp))
    }
}

/** Tiny holder so the when-expression above stays a one-liner per branch. */
private data class Cta(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val action: () -> Unit,
    val gradient: List<Color>,
)
