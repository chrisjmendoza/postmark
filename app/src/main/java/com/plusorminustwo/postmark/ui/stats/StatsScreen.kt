package com.plusorminustwo.postmark.ui.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.data.db.entity.ThreadStatsEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onThreadClick: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val allStats by viewModel.allStats.collectAsState()
    val displayStyle by viewModel.displayStyle.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Style toggle
            SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StatsDisplayStyle.entries.forEachIndexed { index, style ->
                    SegmentedButton(
                        selected = displayStyle == style,
                        onClick = { viewModel.setDisplayStyle(style) },
                        shape = SegmentedButtonDefaults.itemShape(index, StatsDisplayStyle.entries.size),
                        label = { Text(style.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            if (allStats.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No stats yet — send some messages!")
                }
            } else {
                when (displayStyle) {
                    StatsDisplayStyle.NUMBERS -> NumbersView(allStats)
                    StatsDisplayStyle.CHARTS -> ChartsView(allStats)
                    StatsDisplayStyle.HEATMAP -> HeatmapView(allStats)
                }
            }
        }
    }
}

@Composable
private fun NumbersView(stats: List<ThreadStatsEntity>) {
    val total = stats.sumOf { it.totalMessages }
    val sent = stats.sumOf { it.sentCount }
    val received = stats.sumOf { it.receivedCount }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            StatCard("Total Messages", total.toString())
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Sent", sent.toString(), Modifier.weight(1f))
                StatCard("Received", received.toString(), Modifier.weight(1f))
            }
        }
        items(stats) { threadStats ->
            ThreadStatRow(threadStats)
        }
    }
}

@Composable
private fun ChartsView(stats: List<ThreadStatsEntity>) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Charts — coming in next build", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HeatmapView(stats: List<ThreadStatsEntity>) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Heatmap — coming in next build", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThreadStatRow(stats: ThreadStatsEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Thread ${stats.threadId}", style = MaterialTheme.typography.bodyMedium)
            Text("${stats.totalMessages} msgs", style = MaterialTheme.typography.bodySmall)
        }
    }
}
