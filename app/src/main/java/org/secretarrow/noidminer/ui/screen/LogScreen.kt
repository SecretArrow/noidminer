package org.secretarrow.noidminer.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.secretarrow.noidminer.ui.components.SectionHeader
import org.secretarrow.noidminer.viewmodel.LogViewModel
import java.time.Instant

/** Live mining log console (engine + service + stratum raw lines). */
@Composable
fun LogScreen(modifier: Modifier = Modifier, viewModel: LogViewModel = viewModel()) {
    val lines by viewModel.lines.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        SectionHeader("Event log", trailing = {
            Text(
                text = "${lines.size} lines",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        })
        if (lines.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(
                    text = "Belum ada aktivitas.\nMulai mining pada layar Mine — log engine, service, " +
                        "dan pool akan muncul di sini secara live.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // PERF (jetpack-compose-performance skill): remember the
                // reversed list so a new log line does not allocate a new
                // view list on every recomposition, and provide stable
                // keys so LazyColumn diffs only the newly inserted item.
                val reversed = remember(lines) { lines.asReversed() }
                items(reversed, key = { it.seq }) { line ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = Instant.ofEpochMilli(line.ts).toString().substring(11, 19),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(72.dp),
                            )
                            Text(
                                text = line.level.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = when (line.level) {
                                    "error" -> MaterialTheme.colorScheme.error
                                    "warn" -> Color(0xFFFBBF24)
                                    else -> MaterialTheme.colorScheme.secondary
                                },
                                modifier = Modifier.width(52.dp),
                            )
                            Text(
                                text = line.message,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}
