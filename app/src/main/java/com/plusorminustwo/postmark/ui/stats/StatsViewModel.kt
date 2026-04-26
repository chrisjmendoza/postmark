package com.plusorminustwo.postmark.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.sync.StatsUpdater
import com.plusorminustwo.postmark.data.sync.buildGlobalStatsData
import com.plusorminustwo.postmark.data.sync.buildThreadStatsData
import com.plusorminustwo.postmark.data.sync.computeResponseTimeBuckets
import com.plusorminustwo.postmark.data.sync.groupMessagesByDay
import com.plusorminustwo.postmark.data.sync.last56DayLabels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StatsDisplayStyle { NUMBERS, CHARTS, HEATMAP }
enum class StatsScope { GLOBAL, PER_THREAD }

/**
 * UI-facing stats: all values pre-computed to Kotlin types, ready for display.
 * No JSON — sourced directly from [buildThreadStatsData] / [buildGlobalStatsData].
 */
data class ParsedStats(
    val totalMessages: Int = 0,
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    val activeDayCount: Int = 0,
    val longestStreakDays: Int = 0,
    val avgResponseTimeMs: Long = 0L,
    /** Top 6 (emoji, count) pairs sorted descending. */
    val topEmojis: List<Pair<String, Int>> = emptyList(),
    /** Index 0 = Monday … 6 = Sunday. */
    val byDayOfWeek: IntArray = IntArray(7),
    /** Index 0 = January … 11 = December. */
    val byMonth: IntArray = IntArray(12),
    /** Global only — number of threads contributing to these stats. */
    val threadCount: Int = 0,
    /** Per-thread only. */
    val firstMessageAt: Long = 0L,
    val lastMessageAt: Long = 0L
)

/** Pairs heatmap day labels with their message counts for rendering. */
data class HeatmapData(
    /** 56 "yyyy-MM-dd" labels, oldest first. */
    val dayLabels: List<String>,
    /** Sparse map: label → count. Missing key = 0. */
    val countByDay: Map<String, Int>
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val threadDao: ThreadDao,
    private val messageDao: MessageDao,
    private val statsUpdater: StatsUpdater
) : ViewModel() {

    private val heatmapStartMs = System.currentTimeMillis() - 55L * 86_400_000L

    private val _selectedScope = MutableStateFlow(StatsScope.GLOBAL)
    val selectedScope: StateFlow<StatsScope> = _selectedScope

    private val _globalStyle = MutableStateFlow(StatsDisplayStyle.NUMBERS)
    val globalStyle: StateFlow<StatsDisplayStyle> = _globalStyle

    private val _threadStyle = MutableStateFlow(StatsDisplayStyle.NUMBERS)
    val threadStyle: StateFlow<StatsDisplayStyle> = _threadStyle

    private val _selectedThreadId = MutableStateFlow<Long?>(null)
    val selectedThreadId: StateFlow<Long?> = _selectedThreadId

    private val _isRecomputing = MutableStateFlow(false)
    val isRecomputing: StateFlow<Boolean> = _isRecomputing

    // ── Live message source ───────────────────────────────────────────────────
    //
    // Room emits a new list every time any message is inserted/updated/deleted.
    // All stats are derived from this single reactive source — no manual
    // refresh needed.

    private val allMessages: SharedFlow<List<MessageEntity>> =
        messageDao.observeMessagesFrom(0L)
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // ── Global stats (live) ───────────────────────────────────────────────────

    /**
     * Null until Room delivers the first emission (typically < 50 ms after
     * subscription). Null also means "no messages yet".
     */
    val parsedGlobalStats: StateFlow<ParsedStats?> =
        combine(allMessages, threadDao.observeAll()) { msgs, threads ->
            if (msgs.isEmpty()) null
            else buildGlobalStatsData(msgs, threads.size).toParsed()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Per-thread stats sorted by total message count, recomputed live whenever
     * any message changes.
     */
    val allLiveThreadStats: StateFlow<List<Pair<Long, ParsedStats>>> =
        allMessages
            .map { msgs ->
                msgs.groupBy { it.threadId }
                    .map { (threadId, threadMsgs) ->
                        threadId to buildThreadStatsData(threadMsgs).toParsed()
                    }
                    .sortedByDescending { (_, stats) -> stats.totalMessages }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** threadId → displayName for the thread list labels. */
    val threadNames: StateFlow<Map<Long, String>> = threadDao.observeAll()
        .map { threads -> threads.associate { it.id to it.displayName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ── Per-thread drilldown (live) ───────────────────────────────────────────

    /** Full message list for the selected thread — switches automatically. */
    val selectedThreadMessages: StateFlow<List<MessageEntity>> = _selectedThreadId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else messageDao.observeByThread(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Stats for the selected thread, derived live from its messages. */
    val parsedSelectedStats: StateFlow<ParsedStats?> = selectedThreadMessages
        .map { msgs ->
            if (msgs.isEmpty()) null
            else buildThreadStatsData(msgs).toParsed()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [<1min, 1–5min, 5–30min, >30min] response-time distribution. */
    val responseBuckets: StateFlow<IntArray> = selectedThreadMessages
        .map { msgs ->
            if (msgs.isEmpty()) IntArray(4)
            else computeResponseTimeBuckets(msgs.sortedBy { it.timestamp })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntArray(4))

    // ── Heatmap (live) ────────────────────────────────────────────────────────

    val heatmapMessages: StateFlow<List<MessageEntity>> = _selectedThreadId
        .flatMapLatest { id ->
            if (id == null) messageDao.observeMessagesFrom(heatmapStartMs)
            else messageDao.observeMessagesFromForThread(id, heatmapStartMs)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val heatmapData: StateFlow<HeatmapData> = heatmapMessages
        .map { msgs ->
            HeatmapData(dayLabels = last56DayLabels(), countByDay = groupMessagesByDay(msgs))
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HeatmapData(last56DayLabels(), emptyMap())
        )

    // ── Actions ───────────────────────────────────────────────────────────────

    fun selectThread(id: Long?) { _selectedThreadId.value = id }

    fun setScope(scope: StatsScope) {
        _selectedScope.value = scope
        _selectedThreadId.value = null
    }

    fun setDisplayStyle(style: StatsDisplayStyle) {
        when (_selectedScope.value) {
            StatsScope.GLOBAL -> _globalStyle.value = style
            StatsScope.PER_THREAD -> _threadStyle.value = style
        }
    }

    /**
     * Writes computed stats to the persisted stats tables (for future use,
     * e.g. home-screen widgets). Not required for the Stats screen display,
     * which is always live.
     */
    fun recomputeAll() {
        viewModelScope.launch {
            _isRecomputing.value = true
            try {
                statsUpdater.recomputeAll()
            } finally {
                _isRecomputing.value = false
            }
        }
    }

    // ── Data class → ParsedStats ──────────────────────────────────────────────

    private fun com.plusorminustwo.postmark.data.sync.ThreadStatsData.toParsed() = ParsedStats(
        totalMessages     = totalMessages,
        sentCount         = sentCount,
        receivedCount     = receivedCount,
        activeDayCount    = activeDayCount,
        longestStreakDays  = longestStreakDays,
        avgResponseTimeMs = avgResponseTimeMs,
        topEmojis         = topEmojis.entries.map { it.key to it.value },
        byDayOfWeek       = byDayOfWeek,
        byMonth           = byMonth,
        firstMessageAt    = firstMessageAt,
        lastMessageAt     = lastMessageAt
    )

    private fun com.plusorminustwo.postmark.data.sync.GlobalStatsData.toParsed() = ParsedStats(
        totalMessages     = totalMessages,
        sentCount         = sentCount,
        receivedCount     = receivedCount,
        activeDayCount    = activeDayCount,
        longestStreakDays  = longestStreakDays,
        avgResponseTimeMs = avgResponseTimeMs,
        topEmojis         = topEmojis.entries.map { it.key to it.value },
        byDayOfWeek       = byDayOfWeek,
        byMonth           = byMonth,
        threadCount       = threadCount
    )
}
