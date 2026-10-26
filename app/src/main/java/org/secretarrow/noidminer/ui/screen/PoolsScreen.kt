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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.secretarrow.noidminer.data.MineMode
import org.secretarrow.noidminer.ui.components.SectionHeader
import org.secretarrow.noidminer.viewmodel.PoolsViewModel

/** Pool / mode configuration with presets and a live connectivity probe. */
@Composable
fun PoolsScreen(modifier: Modifier = Modifier, viewModel: PoolsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    val probe by viewModel.probe.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var poolUrl by remember(settings.poolUrl) { mutableStateOf(settings.poolUrl) }
    var poolUser by remember(settings.poolUser) { mutableStateOf(settings.poolUser) }
    var worker by remember(settings.workerName) { mutableStateOf(settings.workerName) }
    var soloRpc by remember(settings.soloRpcUrl) { mutableStateOf(settings.soloRpcUrl) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionHeader("Mode")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MineMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    label = {
                        Text(
                            text = when (mode) {
                                MineMode.DEMO -> "Demo"
                                MineMode.POOL -> "Pool"
                                MineMode.SOLO -> "Solo node"
                            },
                        )
                    },
                )
            }
        }

        if (settings.mode == MineMode.POOL) {
            SectionHeader("Pool")
            OutlinedTextField(
                value = poolUrl,
                onValueChange = { poolUrl = it },
                label = { Text("Stratum URL") },
                supportingText = { Text("stratum+tcp:// atau stratum+tls://") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PoolPresetRow(viewModel)
            OutlinedTextField(
                value = poolUser,
                onValueChange = { poolUser = it },
                label = { Text("User (wallet.address)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = worker,
                onValueChange = { worker = it },
                label = { Text("Worker name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (settings.mode == MineMode.SOLO) {
            SectionHeader("Node RPC")
            OutlinedTextField(
                value = soloRpc,
                onValueChange = { soloRpc = it },
                label = { Text("RPC URL") },
                supportingText = { Text("paranoid_getBlockTemplate / paranoid_submitBlock") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(
                onClick = {
                    viewModel.setPoolUrl(poolUrl.trim())
                    viewModel.setPoolUser(poolUser.trim())
                    viewModel.setWorker(worker.trim())
                    viewModel.setSoloRpc(soloRpc.trim())
                },
                enabled = true,
            ) { Text("Save") }
            androidx.compose.material3.OutlinedButton(
                onClick = { viewModel.probe(context) },
            ) { Text("Probe") }
        }

        if (probe.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = androidx.compose.foundation.shape
                    .RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = probe,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Text(
            text = "Catatan: mode Demo berjalan mandiri (tanpa jaringan) untuk menguji " +
                "performa perangkat. Mode Pool memakai Stratum SV1 (TCP/TLS); jika pool " +
                "memakai skema job non-standar, lihat layar Log untuk payload mentah.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun PoolPresetRow(viewModel: PoolsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PoolsViewModel.presets.forEach { preset ->
            Card(
                onClick = { viewModel.applyPreset(context, preset.url) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = androidx.compose.foundation.shape
                    .RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(preset.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        preset.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
