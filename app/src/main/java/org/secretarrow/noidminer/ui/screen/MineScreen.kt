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
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.secretarrow.noidminer.data.BackendPref
import org.secretarrow.noidminer.ui.components.GlassCard
import org.secretarrow.noidminer.ui.components.GradientButton
import org.secretarrow.noidminer.ui.components.HashrateChart
import org.secretarrow.noidminer.ui.components.LiveIndicator
import org.secretarrow.noidminer.ui.components.PillTone
import org.secretarrow.noidminer.ui.components.RadialGauge
import org.secretarrow.noidminer.ui.components.StatCard
import org.secretarrow.noidminer.ui.components.StatusPill
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
 * Start flow: tapping "Start mining" opens a backend picker dialog showing
 * the available CPU (cores, native/software) and GPU (device name,
 * self-check status). The user picks one and the dialog calls
 * onStart(pref) which forwards to MinerService via Intent extra.
 */
@Composable
fun MineScreen(
    onStart: (BackendPref) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MineViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val thermal by viewModel.thermal.collectAsState()
    val backendProbe by viewModel.backendProbe.collectAsState()

    val isRunning = state.running
    val isPaused = state.paused
    // PERF (jetpack-compose-performance skill): extract repeated State reads
    // into local vars. `state.hashrateHps` was read 4 times (gaugeMax,
    // RadialGauge, sparkline, Format.hashrate) — each read is a snapshot
    // access that registers observation. A single local read is cheaper
    // and guarantees all four consumers see the same value within this
    // composition pass.
    val hashrateHps = state.hashrateHps
    val isActive = isRunning && !isPaused

    // Backend picker dialog — shown when the user taps Start. The dialog
    // displays the probed backends (CPU/GPU) and lets the user choose.
    var showBackendPicker by remember { mutableStateOf(false) }

    // CTA configuration — flips between three states with distinct gradients.
    val (ctaLabel, ctaIcon, ctaGradient) = when {
        !isRunning -> CtaConfig("Start mining", Icons.Filled.PlayArrow, GradientHero)
        isPaused -> CtaConfig("Resume", Icons.Filled.PlayArrow, GradientMining)
        else -> CtaConfig("Pause", Icons.Filled.Pause, GradientIdle)
    }
    val ctaAction: () -> Unit = when {
        !isRunning -> {
            { showBackendPicker = true }
        }
        isPaused -> onResume
        else -> onPause
    }

    // Gauge max — adaptive so the arc shows movement but never saturates.
    val gaugeMax = (hashrateHps * 2.0).coerceAtLeast(50.0).toFloat()

    if (showBackendPicker) {
        BackendPickerDialog(
            probe = backendProbe,
            onDismiss = { showBackendPicker = false },
            onSelect = { pref ->
                showBackendPicker = false
                onStart(pref)
            },
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

        // ---------------------------------------------------- status strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveIndicator(active = isActive, label = if (isRunning) "LIVE" else "IDLE")
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
                    value = hashrateHps.toFloat(),
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
                        text = Format.hashrate(hashrateHps),
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
                    value = hashrateHps,
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
            contentColor = if (isActive) MaterialTheme.colorScheme.onSurface else Color.White,
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

/**
 * Backend picker dialog — shown when the user taps Start mining.
 *
 * Lists the available hashing backends (CPU + GPU if present) with their
 * key specs (cores, device name, self-check status) and lets the user
 * pick one. The choice is forwarded to onStart(pref) which routes it
 * through to MinerService via Intent extra.
 *
 * While the probe is loading (first composition), a spinner is shown
 * instead of the list — the probe runs on Dispatchers.Default so the
 * main thread is never blocked.
 */
@Composable
private fun BackendPickerDialog(
    probe: org.secretarrow.noidminer.pow.EngineSelector.BackendProbe?,
    onDismiss: () -> Unit,
    onSelect: (BackendPref) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Pilih backend mining",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            if (probe == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Memeriksa hardware…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // CPU option — always available
                    BackendOption(
                        icon = Icons.Filled.DeveloperBoard,
                        title = "CPU",
                        subtitle = "${probe.cpuCores} core · ${probe.cpuLabel}",
                        badge = null,
                        onClick = { onSelect(BackendPref.CPU_ONLY) },
                    )
                    // Auto option — try GPU, fall back to CPU
                    BackendOption(
                        icon = Icons.Filled.Memory,
                        title = "Auto (GPU → CPU fallback)",
                        subtitle = if (probe.gpuAvailable) {
                            "GPU tersedia, CPU sebagai backup"
                        } else {
                            "GPU tidak tersedia, akan pakai CPU"
                        },
                        badge = if (probe.gpuAvailable) "RECOMMENDED" else null,
                        onClick = { onSelect(BackendPref.AUTO) },
                    )
                    // GPU option — only enabled when self-check passed
                    BackendOption(
                        icon = Icons.Filled.Memory,
                        title = "GPU only",
                        subtitle = when {
                            !probe.gpuAvailable -> "Tidak ada GPU OpenCL terdeteksi"
                            !probe.gpuSelfCheckPassed -> "Self-check gagal — digest GPU ≠ CPU"
                            else -> probe.gpuLabel ?: "GPU"
                        },
                        badge = when {
                            !probe.gpuAvailable -> "OFFLINE"
                            !probe.gpuSelfCheckPassed -> "FAILED"
                            else -> "READY"
                        },
                        enabled = probe.gpuAvailable && probe.gpuSelfCheckPassed,
                        onClick = { onSelect(BackendPref.GPU_ONLY) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
    )
}

@Composable
private fun BackendOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    badge: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (enabled) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    val fg = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = fg.copy(alpha = 0.8f),
            )
        }
        if (badge != null) {
            val (badgeColor, badgeIcon) = when (badge) {
                "READY" -> MaterialTheme.colorScheme.tertiary to Icons.Filled.Verified
                "FAILED" -> MaterialTheme.colorScheme.error to Icons.Filled.WarningAmber
                "OFFLINE" -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Filled.WarningAmber
                else -> MaterialTheme.colorScheme.primary to Icons.Filled.Verified
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Tiny holder so the when-expression above stays a one-liner per branch. */
private data class CtaConfig(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val gradient: List<Color>,
)
