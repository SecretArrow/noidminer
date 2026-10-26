package org.secretarrow.noidminer.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.secretarrow.noidminer.data.BackendPref
import androidx.compose.material3.FilterChip
import org.secretarrow.noidminer.ui.components.SectionHeader
import org.secretarrow.noidminer.viewmodel.SettingsViewModel

/** Mining behavior: threads, backend, guards, wake lock, auto-start. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.attach(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionHeader("Threads")
        Text(
            text = if (settings.threads == 0) {
                "Auto (${viewModel.suggestedThreads()} threads)"
            } else {
                "${settings.threads} threads"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = settings.threads.toFloat(),
            onValueChange = { viewModel.setThreads(it.toInt()) },
            valueRange = 0f..16f,
            steps = 15,
        )
        Text(
            text = "0 = auto. Saran: biarkan auto agar mengikuti cluster performa CPU.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("Backend")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackendPref.entries.forEach { pref ->
                FilterChip(
                    selected = settings.backendPref == pref,
                    onClick = { viewModel.setBackend(pref) },
                    label = {
                        Text(
                            text = when (pref) {
                                BackendPref.AUTO -> "Auto (GPU→CPU)"
                                BackendPref.CPU_ONLY -> "CPU"
                                BackendPref.GPU_ONLY -> "GPU"
                            },
                        )
                    },
                )
            }
        }

        SectionHeader("Battery & thermal guards")
        ToggleRow(
            label = "Pause below battery %",
            checked = settings.pauseAtBatteryPct in 1..100,
            onChecked = { viewModel.setPauseAtBattery(if (it) 15 else 0) },
        )
        if (settings.pauseAtBatteryPct in 1..100) {
            Text(
                text = "Pause at ${settings.pauseAtBatteryPct}%",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = settings.pauseAtBatteryPct.toFloat(),
                onValueChange = { viewModel.setPauseAtBattery(it.toInt()) },
                valueRange = 5f..60f,
                steps = 54,
            )
        }
        ToggleRow(
            label = "Only mine while charging",
            checked = settings.onlyWhenCharging,
            onChecked = { viewModel.setOnlyCharging(it) },
        )
        ToggleRow(
            label = "Respect battery saver",
            checked = settings.respectBatterySaver,
            onChecked = { viewModel.setRespectSaver(it) },
        )

        SectionHeader("Background")
        ToggleRow(
            label = "Keep CPU awake (partial wake lock)",
            checked = settings.keepCpuAwake,
            onChecked = { viewModel.setKeepAwake(it) },
        )
        ToggleRow(
            label = "Auto-start after reboot",
            checked = settings.autoStartOnBoot,
            onChecked = { viewModel.setAutoBoot(context, it) },
        )
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
