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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.secretarrow.noidminer.ui.components.HashrateChart
import org.secretarrow.noidminer.ui.components.StatCard
import org.secretarrow.noidminer.ui.theme.GradientStops
import org.secretarrow.noidminer.util.Format
import org.secretarrow.noidminer.viewmodel.MineViewModel

/**
 * Mine — the hero screen: animated hashrate, sparkline, share counters,
 * backend/pool chips, and the big start/pause/stop control.
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

    val (icon, label, action, contentColor) = when {
        !state.running -> Quad(Icons.Filled.PlayArrow, "Start mining", onStart, Color.White)
        state.paused -> Quad(Icons.Filled.PlayArrow, "Resume", onResume, Color.White)
        else -> Quad(Icons.Filled.Pause, "Pause", onPause, MaterialTheme.colorScheme.onSurface)
    }
    val heroBrush = when {
        !state.running -> Brush.horizontalGradient(GradientStops)
        state.paused -> Brush.horizontalGradient(
            listOf(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary),
        )
        else -> Brush.horizontalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ------------------------------------------------------ hero card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
        ) {
            HashrateChart(
                value = state.hashrateHps,
                modifier = Modifier.fillMaxSize().padding(top = 24.dp),
            )
            Column(modifier = Modifier.align(Alignment.TopStart).padding(20.dp)) {
                Text(
                    text = "LIVE HASHRATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = Format.hashrate(state.hashrateHps),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = when {
                        !state.running -> "idle"
                        state.paused -> "paused · ${state.pausedReason ?: ""}"
                        else -> "${state.backend} · h=${state.currentHeight}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                value = if (state.running) Format.duration(System.currentTimeMillis() - state.startedAtMs) else "—",
                modifier = Modifier.weight(1f),
                hint = "${Format.compact(state.hashesTried)} hashes",
            )
        }

        // --------------------------------------------------- main control
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(heroBrush)
                .clickable(onClick = action),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(30.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
            }
        }

        if (state.running) {
            Text(
                text = "Stop",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onStop)
                    .padding(vertical = 12.dp),
            )
        }

        // --------------------------------------------------- thermal chip
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Thermostat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Thermal: $thermal",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.gpuAvailable) "GPU ready" else "CPU mode",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.height(96.dp))
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
