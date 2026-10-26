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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.secretarrow.noidminer.ui.components.SectionHeader
import org.secretarrow.noidminer.ui.components.StatCard
import org.secretarrow.noidminer.viewmodel.DeviceViewModel

/** Device screen: SoC, CPU clusters, GPU/OpenCL, thermal & battery status. */
@Composable
fun DeviceScreen(modifier: Modifier = Modifier, viewModel: DeviceViewModel = viewModel()) {
    val hardware by viewModel.hardware.collectAsState()
    val gpu by viewModel.gpu.collectAsState()
    val battery by viewModel.battery.collectAsState()
    val bench by viewModel.benchmark.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.attach(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionHeader("Processor")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("SoC", hardware.socName)
                InfoRow("ABI", hardware.abi)
                InfoRow("Cores", hardware.coreCount.toString())
                InfoRow("PMULL", if (hardware.hasNeonPmull) "yes (ARMv8 crypto)" else "no")
                InfoRow("RAM", "${hardware.ramTotalMb} MB")
                InfoRow("Suggested threads", hardware.suggestedThreads().toString())
            }
        }
        hardware.clusters.forEach { cluster ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = cluster.label,
                    value = "${cluster.cores} core",
                    modifier = Modifier.weight(1f),
                    hint = if (cluster.maxFreqKHz > 0) "${cluster.maxFreqKHz / 1000} MHz" else "freq n/a",
                )
            }
        }

        SectionHeader("GPU (OpenCL)")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("Device", gpu.device ?: "tidak terdeteksi")
                InfoRow("Status", gpu.status)
                InfoRow("Self-check", gpu.selfCheck)
            }
        }

        SectionHeader("Battery & thermal")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Level", "${battery.pct}%")
                InfoRow("Charging", if (battery.charging) "yes" else "no")
                InfoRow("Power saver", if (battery.saver) "on" else "off")
                InfoRow("Thermal", battery.thermal)
                LinearProgressIndicator(
                    progress = { battery.pct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionHeader("Benchmark")
        Button(onClick = { viewModel.runBenchmark() }, enabled = !bench.running) {
            Text(if (bench.running) "Running…" else "Run 3s benchmark")
        }
        bench.result?.let {
            Text(
                text = "Result: $it",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
