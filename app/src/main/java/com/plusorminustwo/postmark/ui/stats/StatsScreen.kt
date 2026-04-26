package com.plusorminustwo.postmark.ui.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onThreadClick: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val selectedScope by viewModel.selectedScope.collectAsState()
    val selectedThreadId by viewModel.selectedThreadId.collectAsState()
    val globalStyle by viewModel.globalStyle.collectAsState()
    val threadStyle by viewModel.threadStyle.collectAsState()
    val parsedGlobal by viewModel.parsedGlobalStats.collectAsState()
    val parsedSelected by viewModel.parsedSelectedStats.collectAsState()
    val threadNames by viewModel.threadNames.collectAsState()
    val allLiveThreadStats by viewModel.allLiveThreadStats.collectAsState()
    val heatmapData by viewModel.heatmapData.collectAsState()
    val responseBuckets by viewModel.responseBuckets.collectAsState()
    val isRecomputing by viewModel.isRecomputing.collectAsState()

    val isInDrilldown = selectedScope == StatsScope.PER_THREAD && selectedThreadId != null
    val isInThreadList = selectedScope == StatsScope.PER_THREAD && selectedThreadId == null
    val activeStyle = if (selectedScope == StatsScope.GLOBAL) globalStyle else threadStyle
    val currentStats = if (isInDrilldown) parsedSelected else parsedGlobal
    val threadTitle = selectedThreadId?.let { threadNames[it] }

    BackHandler(enabled = isInDrilldown) { viewModel.selectThread(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isInDrilldown) (threadTitle ?: "Thread") else "Stats") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isInDrilldown) viewModel.selectThread(null) else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Scope toggle — hidden when inside a thread drilldown
            if (!isInDrilldown) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    StatsScope.entries.forEachIndexed { index, scope ->
                        SegmentedButton(
                            selected = selectedScope == scope,
                            onClick = { viewModel.setScope(scope) },
                            shape = SegmentedButtonDefaults.itemShape(index, StatsScope.entries.size),
                            label = {
                                Text(
                                    if (scope == StatsScope.GLOBAL) "All conversations"
                                    else "Per thread"
                                )
                            }
                        )
                    }
                }
            }

            // Style toggle — hidden on the thread list (not needed until a thread is tapped)
            if (!isInThreadList) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    StatsDisplayStyle.entries.forEachIndexed { index, style ->
                        SegmentedButton(
                            selected = activeStyle == style,
                            onClick = { viewModel.setDisplayStyle(style) },
                            shape = SegmentedButtonDefaults.itemShape(index, StatsDisplayStyle.entries.size),
                            label = { Text(style.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            if (isRecomputing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                isInThreadList -> ThreadListView(
                    allLiveThreadStats = allLiveThreadStats,
                    threadNames = threadNames,
                    onThreadSelect = { viewModel.selectThread(it) }
                )
                parsedGlobal == null -> EmptyStatsView()
                else -> {
                    val stats = currentStats ?: parsedGlobal!!
                    when (activeStyle) {
                        StatsDisplayStyle.NUMBERS -> NumbersView(
                            stats = stats,
                            allLiveThreadStats = if (!isInDrilldown) allLiveThreadStats else emptyList(),
                            threadNames = threadNames,
                            responseBuckets = if (isInDrilldown) responseBuckets else null,
                            onThreadSelect = { viewModel.selectThread(it) }
                        )
                        StatsDisplayStyle.CHARTS  -> ChartsView(stats = stats)
                        StatsDisplayStyle.HEATMAP -> HeatmapView(
                            data  = heatmapData,
                            stats = stats
                        )
                    }
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyStatsView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("No messages yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Stats will appear once messages are synced",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Per-thread list ───────────────────────────────────────────────────────────

@Composable
private fun ThreadListView(
    allLiveThreadStats: List<Pair<Long, ParsedStats>>,
    threadNames: Map<Long, String>,
    onThreadSelect: (Long) -> Unit
) {
    if (allLiveThreadStats.isEmpty()) {
        EmptyStatsView()
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(allLiveThreadStats) { (threadId, stats) ->
            ThreadStatRow(
                threadId    = threadId,
                stats       = stats,
                displayName = threadNames[threadId] ?: "Thread $threadId",
                onClick     = { onThreadSelect(threadId) }
            )
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        }
    }
}

// ── Numbers view ──────────────────────────────────────────────────────────────

@Composable
private fun NumbersView(
    stats: ParsedStats,
    allLiveThreadStats: List<Pair<Long, ParsedStats>>,
    threadNames: Map<Long, String>,
    responseBuckets: IntArray?,
    onThreadSelect: (Long) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Messages", stats.totalMessages.toString(), Modifier.weight(1f))
                StatCard("Sent", stats.sentCount.toString(), Modifier.weight(1f))
                StatCard("Received", stats.receivedCount.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Active Days", stats.activeDayCount.toString(), Modifier.weight(1f))
                StatCard("Longest Streak", "${stats.longestStreakDays}d", Modifier.weight(1f))
                StatCard("Avg Response", formatDuration(stats.avgResponseTimeMs), Modifier.weight(1f))
            }
        }
        if (stats.threadCount > 0) {
            item {
                StatCard("Conversations", stats.threadCount.toString())
            }
        }
        if (stats.topEmojis.isNotEmpty()) {
            item { EmojiCard(stats.topEmojis) }
        }
        item {
            ChartCard("Most Active Day") {
                BarChart(
                    values = stats.byDayOfWeek,
                    labels = listOf("M", "T", "W", "T", "F", "S", "S"),
                    modifier = Modifier.fillMaxWidth().height(90.dp)
                )
            }
        }
        // Response time breakdown — drilldown only, at the bottom
        if (responseBuckets != null && responseBuckets.sum() > 0) {
            item { ResponseTimeBreakdownCard(responseBuckets) }
        }
        if (allLiveThreadStats.isNotEmpty()) {
            item {
                Text(
                    "Conversations",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(allLiveThreadStats) { (threadId, threadStats) ->
                ThreadStatRow(
                    threadId    = threadId,
                    stats       = threadStats,
                    displayName = threadNames[threadId] ?: "Thread $threadId",
                    onClick     = { onThreadSelect(threadId) }
                )
            }
        }
    }
}

// ── Charts view ───────────────────────────────────────────────────────────────

@Composable
private fun ChartsView(stats: ParsedStats) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ChartCard("Messages by Month") {
                BarChart(
                    values = stats.byMonth,
                    labels = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"),
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )
            }
        }
        item {
            ChartCard("Most Active Day") {
                BarChart(
                    values = stats.byDayOfWeek,
                    labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
                    modifier = Modifier.fillMaxWidth().height(90.dp)
                )
            }
        }
        if (stats.topEmojis.isNotEmpty()) {
            item { EmojiCard(stats.topEmojis) }
        }
    }
}

// ── Heatmap view ──────────────────────────────────────────────────────────────

private val HEATMAP_COLORS = listOf(
    Color(0xFF2C2C2E),  // tier 0 — no messages
    Color(0xFF1C3A5A),  // tier 1
    Color(0xFF1A4E7A),  // tier 2
    Color(0xFF1F62A0),  // tier 3
    Color(0xFF2676C5),  // tier 4
    Color(0xFF2E8AD1),  // tier 5
    Color(0xFF378ADD)   // tier 6 — most active
)

@Composable
private fun HeatmapView(data: HeatmapData, stats: ParsedStats) {
    val dayLabels = data.dayLabels
    val countByDay = data.countByDay

    val firstDate = remember(dayLabels) { LocalDate.parse(dayLabels.first()) }
    val startPadding = (firstDate.dayOfWeek.value - 1) % 7  // Mon=0 .. Sun=6
    val totalCells = startPadding + dayLabels.size

    val totalInWindow = countByDay.values.sum()
    val mostActiveDay = countByDay.entries.maxByOrNull { it.value }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(totalCells) { index ->
                val dayIndex = index - startPadding
                if (dayIndex < 0) {
                    Spacer(Modifier.aspectRatio(1f))
                } else {
                    val label = dayLabels[dayIndex]
                    val count = countByDay[label] ?: 0
                    val tier = heatmapTierForCount(count)
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(HEATMAP_COLORS[tier])
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "$totalInWindow messages in last 8 weeks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (mostActiveDay != null && mostActiveDay.value > 0) {
            Text(
                "Most active: ${mostActiveDay.key} (${mostActiveDay.value} msgs)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Less", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HEATMAP_COLORS.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
            }
            Text("More", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Active Days", stats.activeDayCount.toString(), Modifier.weight(1f))
            StatCard("Streak", "${stats.longestStreakDays}d", Modifier.weight(1f))
        }
    }
}

// ── Reusable card composables ─────────────────────────────────────────────────

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun EmojiCard(topEmojis: List<Pair<String, Int>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Top Emoji", style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                topEmojis.forEach { (emoji, count) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(emoji, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private val RESPONSE_TIME_TIERS = listOf(
    Triple("Under 1 min", Color(0xFF30D158), 0),
    Triple("1–5 min",     Color(0xFF378ADD), 1),
    Triple("5–30 min",    Color(0xFFFF9F0A), 2),
    Triple("Over 30 min", Color(0xFF636366), 3),
)

@Composable
private fun ResponseTimeBreakdownCard(buckets: IntArray) {
    val total = buckets.sum().coerceAtLeast(1)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Response Times", style = MaterialTheme.typography.titleSmall)
            RESPONSE_TIME_TIERS.forEach { (label, color, idx) ->
                val count = buckets.getOrElse(idx) { 0 }
                val fraction = count.toFloat() / total
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Text(
                        label,
                        modifier = Modifier.width(80.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .clip(RoundedCornerShape(3.dp))
                                .background(color)
                        )
                    }
                    Text(
                        "${(fraction * 100).toInt()}%",
                        modifier = Modifier.width(32.dp),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadStatRow(
    threadId: Long,
    stats: ParsedStats,
    displayName: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${stats.activeDayCount} active days · streak ${stats.longestStreakDays}d",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            stats.totalMessages.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Bar chart ─────────────────────────────────────────────────────────────────

@Composable
private fun BarChart(
    values: IntArray,
    labels: List<String>,
    modifier: Modifier = Modifier
) {
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val barColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            values.forEachIndexed { i, value ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .fillMaxHeight(value.toFloat() / max)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(barColor)
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    maxLines = 1
                )
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun heatmapTierForCount(count: Int): Int = when {
    count <= 0  -> 0
    count <= 2  -> 1
    count <= 4  -> 2
    count <= 6  -> 3
    count <= 9  -> 4
    count <= 14 -> 5
    else        -> 6
}

private fun formatDuration(ms: Long): String = when {
    ms <= 0          -> "—"
    ms < 60_000      -> "${ms / 1000}s"
    ms < 3_600_000   -> "${ms / 60_000}m"
    else             -> "${ms / 3_600_000}h ${(ms % 3_600_000) / 60_000}m"
}
