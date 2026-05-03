package com.plusorminustwo.postmark.ui.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.sync.heatmapTierForCount
import com.plusorminustwo.postmark.ui.components.LetterAvatar
import com.plusorminustwo.postmark.ui.components.avatarColor
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onOpenThreadAt: (threadId: Long, scrollToMessageId: Long, scrollToDate: String) -> Unit = { _, _, _ -> },
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
    val heatmapMonth by viewModel.heatmapMonth.collectAsState()
    val selectedHeatmapDays by viewModel.selectedHeatmapDays.collectAsState()
    val selectedDayMessages by viewModel.selectedDayMessages.collectAsState()
    val allThreadMessages   by viewModel.selectedThreadMessages.collectAsState()
    val heatmapByDayOfWeek  by viewModel.heatmapByDayOfWeek.collectAsState()
    val heatmapMessages     by viewModel.heatmapMessages.collectAsState()
    val directThreadNavigation by viewModel.directThreadNavigation.collectAsState()
    val responseBuckets by viewModel.responseBuckets.collectAsState()
    val isRecomputing by viewModel.isRecomputing.collectAsState()

    val isInDrilldown = selectedScope == StatsScope.PER_THREAD && selectedThreadId != null
    val isInThreadList = selectedScope == StatsScope.PER_THREAD && selectedThreadId == null
    val activeStyle = if (selectedScope == StatsScope.GLOBAL) globalStyle else threadStyle
    val currentStats = if (isInDrilldown) parsedSelected else parsedGlobal
    val threadTitle = selectedThreadId?.let { threadNames[it] }

    BackHandler(enabled = isInDrilldown && !directThreadNavigation) { viewModel.selectThread(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isInDrilldown) (threadTitle ?: "Thread") else "Stats") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isInDrilldown && !directThreadNavigation) viewModel.selectThread(null)
                        else onBack()
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
                        StatsDisplayStyle.HEATMAP -> {
                            val tid = selectedThreadId
                            HeatmapView(
                                data                = heatmapData,
                                stats               = stats,
                                month               = heatmapMonth,
                                selectedDays        = selectedHeatmapDays,
                                selectedDayMessages = selectedDayMessages,
                                allThreadMessages   = allThreadMessages,
                                threadNames         = threadNames,
                                isGlobal            = !isInDrilldown,
                                monthMessages       = heatmapMessages,
                                byDayOfWeek         = heatmapByDayOfWeek,
                                onMonthChange       = { viewModel.setHeatmapMonth(it) },
                                onDayToggle         = { viewModel.toggleHeatmapDay(it) },
                                onClearSelection    = { viewModel.clearHeatmapDays() },
                                onContactClick      = { viewModel.selectThread(it) },
                                onNavigateToThread  = if (isInDrilldown && tid != null) { msgId, date ->
                                    if (msgId != null) onOpenThreadAt(tid, msgId, "")
                                    else if (date != null) onOpenThreadAt(tid, -1L, date.toString())
                                } else null
                            )
                        }
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
        item { EmojiCard("Top Emoji (Messages)", stats.topEmojis) }
        item { EmojiCard("Top Emoji (Reactions)", stats.topReactionEmojis) }
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
        item { EmojiCard("Top Emoji (Messages)", stats.topEmojis) }
        item { EmojiCard("Top Emoji (Reactions)", stats.topReactionEmojis) }
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
private fun HeatmapView(
    data: HeatmapData,
    stats: ParsedStats,
    month: YearMonth,
    selectedDays: Set<LocalDate>,
    selectedDayMessages: List<MessageEntity>,
    allThreadMessages: List<MessageEntity>,
    threadNames: Map<Long, String>,
    isGlobal: Boolean,
    monthMessages: List<MessageEntity>,
    byDayOfWeek: IntArray,
    onMonthChange: (YearMonth) -> Unit,
    onDayToggle: (LocalDate) -> Unit,
    onClearSelection: () -> Unit,
    onContactClick: (Long) -> Unit = {},
    onNavigateToThread: ((messageId: Long?, date: LocalDate?) -> Unit)? = null
) {
    val countByDay = data.countByDay
    val firstDate = remember(month) { month.atDay(1) }
    val startPadding = (firstDate.dayOfWeek.value - 1) % 7  // Mon=0 .. Sun=6

    val cells = remember(month, startPadding) {
        List<String?>(startPadding) { null } +
            (1..month.lengthOfMonth()).map { month.atDay(it).toString() }
    }
    val weeks = remember(cells) { cells.chunked(7) }

    val monthHeaderFmt  = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    val fullDateFmt     = remember { DateTimeFormatter.ofPattern("EEEE, MMMM d") }

    val totalInMonth    = countByDay.values.sum()
    val activeDaysCount = countByDay.count { it.value > 0 }
    val dailyAvg        = if (month.lengthOfMonth() > 0) totalInMonth.toFloat() / month.lengthOfMonth() else 0f

    // When days are selected, override the summary cards with the selection totals
    val hasSelection       = selectedDays.isNotEmpty()
    val selectedDayTotal   = if (hasSelection) selectedDays.sumOf { countByDay[it.toString()] ?: 0 } else totalInMonth
    val selectedActiveDays = if (hasSelection) selectedDays.count { (countByDay[it.toString()] ?: 0) > 0 } else activeDaysCount
    val cardTopLabel = when {
        !hasSelection          -> "This month"
        selectedDays.size == 1 -> selectedDays.first().format(DateTimeFormatter.ofPattern("MMM d"))
        else                   -> "${selectedDays.size} days"
    }
    val cardAvgLabel = if (hasSelection && selectedDays.size > 1) "Avg / day" else if (hasSelection) "That day" else "Daily avg"
    val cardAvgValue = when {
        !hasSelection              -> String.format("%.1f", dailyAvg)
        selectedDays.size == 1     -> selectedDayTotal.toString()
        else                       -> String.format("%.1f", selectedDayTotal.toFloat() / selectedDays.size)
    }

    // Top contacts for the current month (global no-selection panel)
    val allMonthContacts = remember(monthMessages) {
        monthMessages
            .groupBy { it.threadId }
            .entries
            .sortedByDescending { it.value.size }
            .map { it.key to it.value.size }
    }
    val topMonthContacts = remember(allMonthContacts) { allMonthContacts.take(3) }
    val monthTotal = monthMessages.size
    var showAllMonthContacts by remember(monthMessages) { mutableStateOf(false) }

    // All messages grouped by day: newest day first, oldest message first within each day
    val allMessagesByDay = remember(allThreadMessages) {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .also { it.timeZone = java.util.TimeZone.getDefault() }
        allThreadMessages
            .sortedBy { it.timestamp }          // oldest message at top within a day
            .groupBy { fmt.format(java.util.Date(it.timestamp)) }
            .entries
            .sortedByDescending { it.key }      // newest day first in the list
            .map { (dayStr, msgs) -> LocalDate.parse(dayStr) to msgs }
    }
    var showAllMessages by remember(allThreadMessages) { mutableStateOf(false) }
    // Track which days are collapsed in each panel — reset when underlying data changes
    var collapsedAllDays by remember(allThreadMessages) { mutableStateOf(emptySet<LocalDate>()) }
    var collapsedSelectedDays by remember(selectedDays) { mutableStateOf(emptySet<LocalDate>()) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {

        // ── Month navigation ──────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
                }
                Text(
                    text = firstDate.format(monthHeaderFmt),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                IconButton(
                    onClick = { onMonthChange(month.plusMonths(1)) },
                    enabled = month < YearMonth.now()
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
                }
            }
            Spacer(Modifier.height(4.dp))
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
        }

        // ── Calendar weeks ────────────────────────────────────────────────────
        items(weeks) { week ->
            val paddedWeek = week + List(7 - week.size) { null }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                paddedWeek.forEach { label ->
                    if (label == null) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(vertical = 1.5.dp)
                        )
                    } else {
                        val date      = LocalDate.parse(label)
                        val count     = countByDay[label] ?: 0
                        val tier      = heatmapTierForCount(count)
                        val dayNum    = date.dayOfMonth
                        val isSelected = date in selectedDays
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(vertical = 1.5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .then(
                                    if (isSelected)
                                        Modifier.border(2.dp, Color.White, RoundedCornerShape(3.dp))
                                    else Modifier
                                )
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else HEATMAP_COLORS[tier]
                                )
                                .clickable { onDayToggle(date) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dayNum.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    tier == 0  -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    else       -> Color.White.copy(alpha = 0.85f)
                                },
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // ── Legend + summary cards ────────────────────────────────────────────
        item {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(cardTopLabel, selectedDayTotal.toString(), Modifier.weight(1f))
                StatCard("Active days", selectedActiveDays.toString(), Modifier.weight(1f))
                StatCard(cardAvgLabel, cardAvgValue, Modifier.weight(1f))
            }
            if (hasSelection) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onClearSelection,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Text("Clear selection (${selectedDays.size})", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // ── No-selection contacts panel (global only) ────────────────────────
        if (!hasSelection && isGlobal && topMonthContacts.isNotEmpty()) {
            item(key = "global_month_contacts") {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                val monthLabel = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                ContactsCard(
                    headerLabel    = monthLabel,
                    totalCount     = monthTotal,
                    contacts       = if (showAllMonthContacts) allMonthContacts else topMonthContacts,
                    threadNames    = threadNames,
                    hiddenCount    = if (showAllMonthContacts) 0 else (allMonthContacts.size - 3).coerceAtLeast(0),
                    onContactClick = onContactClick,
                    onExpand       = { showAllMonthContacts = true },
                    onCollapse     = { showAllMonthContacts = false }
                )
            }
        }

        // ── No-selection all-messages panel (per-thread only) ──────────────────
        if (!hasSelection && !isGlobal && allMessagesByDay.isNotEmpty()) {
            // Build truncated view: include whole day groups until total > 30
            val visibleDays = if (showAllMessages) allMessagesByDay else run {
                var running = 0
                val result  = mutableListOf<Pair<LocalDate, List<com.plusorminustwo.postmark.data.db.entity.MessageEntity>>>()
                for (pair in allMessagesByDay) {
                    result += pair
                    running += pair.second.size
                    if (running >= 30) break
                }
                result
            }
            val hiddenCount = if (showAllMessages) 0
                else allMessagesByDay.sumOf { it.second.size } - visibleDays.sumOf { it.second.size }

            item(key = "all_panel_header") {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                // ── Expand all / Collapse all button ──────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    val allExpanded = collapsedAllDays.isEmpty()
                    TextButton(onClick = {
                        // Toggle: if everything is expanded, collapse all visible days;
                        // if anything is collapsed, expand everything.
                        collapsedAllDays = if (allExpanded)
                            visibleDays.map { it.first }.toSet()
                        else
                            emptySet()
                    }) {
                        Icon(
                            imageVector = if (allExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (allExpanded) "Collapse all" else "Expand all",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            visibleDays.forEach { (day, msgs) ->
                // Evaluate collapsed state outside the item lambda so it controls
                // whether the items(...) block below is registered at all.
                val isCollapsed = day in collapsedAllDays
                item(key = "all_header_$day") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Tap the header to toggle collapse; tap individual messages to navigate
                            .clickable {
                                collapsedAllDays =
                                    if (isCollapsed) collapsedAllDays - day
                                    else collapsedAllDays + day
                            }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(day.format(fullDateFmt), style = MaterialTheme.typography.titleSmall)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                msgs.size.toString(),
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF378ADD)
                            )
                            Icon(
                                imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                // Only register message items when the day is not collapsed
                if (!isCollapsed) {
                    items(msgs, key = { "all_msg_${it.id}" }) { msg ->
                        DayMessageRow(
                            msg         = msg,
                            threadNames = threadNames,
                            onClick     = onNavigateToThread?.let { cb -> { cb(msg.id, null) } }
                        )
                    }
                }
            }
            if (hiddenCount > 0) {
                item(key = "all_load_more") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick  = { showAllMessages = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Load $hiddenCount more messages",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // ── Day detail panel ──────────────────────────────────────────────────
        if (hasSelection) {
            val sortedDays = selectedDays.sorted()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .also { it.timeZone = java.util.TimeZone.getDefault() }

            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
            }

            if (selectedDayMessages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No messages on selected days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (isGlobal) {
                // Global: per-contact breakdown in a card
                val contactList = selectedDayMessages
                    .groupBy { it.threadId }
                    .entries
                    .sortedByDescending { it.value.size }
                    .map { it.key to it.value.size }
                val cardHeader = when (selectedDays.size) {
                    1    -> selectedDays.first().format(fullDateFmt)
                    else -> "${selectedDays.size} days selected"
                }
                item(key = "global_day_card") {
                    ContactsCard(
                        headerLabel    = cardHeader,
                        totalCount     = selectedDayTotal,
                        contacts       = contactList,
                        threadNames    = threadNames,
                        onContactClick = onContactClick
                    )
                }
            } else {
                // Per-thread: collapsible day groups, oldest message on top
                item(key = "selected_expand_collapse") {
                    // Expand all / Collapse all button for the selected-day panel
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        val allExpanded = collapsedSelectedDays.isEmpty()
                        TextButton(onClick = {
                            collapsedSelectedDays = if (allExpanded)
                                sortedDays.toSet()
                            else
                                emptySet()
                        }) {
                            Icon(
                                imageVector = if (allExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (allExpanded) "Collapse all" else "Expand all",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                sortedDays.sortedDescending().forEach { day ->
                    val isCollapsed = day in collapsedSelectedDays
                    val dayMsgs = selectedDayMessages
                        .filter { msg -> fmt.format(java.util.Date(msg.timestamp)) == day.toString() }
                        .sortedBy { it.timestamp }   // oldest message on top
                    val dayCount = dayMsgs.size

                    item(key = "header_$day") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Tap header to toggle collapse
                                .clickable {
                                    collapsedSelectedDays =
                                        if (isCollapsed) collapsedSelectedDays - day
                                        else collapsedSelectedDays + day
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(day.format(fullDateFmt), style = MaterialTheme.typography.titleSmall)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    dayCount.toString(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFF378ADD)
                                )
                                Icon(
                                    imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                    contentDescription = if (isCollapsed) "Expand" else "Collapse",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (!isCollapsed) {
                        if (dayMsgs.isEmpty()) {
                            item(key = "empty_$day") {
                                Text(
                                    "No messages",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                                )
                            }
                        } else {
                            items(dayMsgs, key = { "msg_${it.id}" }) { msg ->
                                DayMessageRow(
                                    msg         = msg,
                                    threadNames = threadNames,
                                    onClick     = onNavigateToThread?.let { cb -> { cb(msg.id, null) } }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── By day of week (global, always visible) ────────────────────────
        if (isGlobal) {
            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                val monthLabel = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                Text(
                    "BY DAY OF WEEK — ${monthLabel.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                val dowLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                HorizontalBarChart(
                    values = byDayOfWeek,
                    labels = dowLabels
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Day detail composables ────────────────────────────────────────────────────

@Composable
private fun DayMessageRow(msg: MessageEntity, threadNames: Map<Long, String>, onClick: (() -> Unit)? = null) {
    val timeLabel = remember(msg.timestamp) {
        Instant.ofEpochMilli(msg.timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(DateTimeFormatter.ofPattern("h:mm a"))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = if (msg.isSent) "You" else (threadNames[msg.threadId] ?: "Them"),
                style = MaterialTheme.typography.labelSmall,
                color = if (msg.isSent) Color(0xFF378ADD) else Color(0xFF8E8E93)
            )
            Text(
                text = msg.body,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF5F5F0)
            )
        }
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF636366)
        )
    }
}

@Composable
private fun ContactDayRow(
    name: String,
    count: Int,
    fraction: Float,
    onClick: () -> Unit
) {
    val barColor = avatarColor(name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LetterAvatar(name = name, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$count ${if (count == 1) "message" else "messages"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
        }
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = barColor,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 24.dp)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ContactsCard(
    headerLabel: String,
    totalCount: Int,
    contacts: List<Pair<Long, Int>>,
    threadNames: Map<Long, String>,
    hiddenCount: Int = 0,
    onContactClick: (Long) -> Unit,
    onExpand: (() -> Unit)? = null,
    onCollapse: (() -> Unit)? = null
) {
    val maxCount = contacts.maxOfOrNull { it.second } ?: 1
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    headerLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "$totalCount ${if (totalCount == 1) "message" else "messages"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF378ADD)
                )
            }
            contacts.forEachIndexed { index, (threadId, count) ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
                ContactDayRow(
                    name     = threadNames[threadId] ?: "Unknown",
                    count    = count,
                    fraction = count.toFloat() / maxCount,
                    onClick  = { onContactClick(threadId) }
                )
            }
            // Expand / collapse footer
            if (hiddenCount > 0 && onExpand != null) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onExpand)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = "Show all",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Show $hiddenCount more",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (hiddenCount == 0 && onCollapse != null && contacts.size > 3) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCollapse)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandLess,
                        contentDescription = "Show less",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Show less",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
private fun EmojiCard(title: String, topEmojis: List<Pair<String, Int>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (topEmojis.isEmpty()) {
                Text(
                    "None yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
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

// ── Horizontal bar chart (day-of-week breakdown) ────────────────────────

@Composable
private fun HorizontalBarChart(values: IntArray, labels: List<String>) {
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEachIndexed { i, value ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    labels.getOrElse(i) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.End
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(if (max > 0) value.toFloat() / max else 0f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF378ADD))
                    )
                }
                Text(
                    if (value > 0) value.toString() else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                    textAlign = TextAlign.Start
                )
            }
        }
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

private fun formatDuration(ms: Long): String = when {
    ms <= 0          -> "—"
    ms < 60_000      -> "${ms / 1000}s"
    ms < 3_600_000   -> "${ms / 60_000}m"
    else             -> "${ms / 3_600_000}h ${(ms % 3_600_000) / 60_000}m"
}
