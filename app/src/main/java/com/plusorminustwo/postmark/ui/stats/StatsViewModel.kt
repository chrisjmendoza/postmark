package com.plusorminustwo.postmark.ui.stats

import androidx.lifecycle.SavedStateHandle
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    private val statsUpdater: StatsUpdater,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

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

    // ── Heatmap month navigation ──────────────────────────────────────────────

    private val _heatmapMonth = MutableStateFlow(YearMonth.now())
    val heatmapMonth: StateFlow<YearMonth> = _heatmapMonth

    private val _selectedHeatmapDays = MutableStateFlow<Set<LocalDate>>(emptySet())
    val selectedHeatmapDays: StateFlow<Set<LocalDate>> = _selectedHeatmapDays

    /** True when navigated directly from a thread (back goes to thread, not thread list). */
    private val _directThreadNavigation = MutableStateFlow(false)
    val directThreadNavigation: StateFlow<Boolean> = _directThreadNavigation

    /** The scope the user was in before entering a drilldown via [selectThread]. Used to restore
     *  on back so tapping a thread from GLOBAL returns to GLOBAL, not the PER_THREAD list. */
    private val _originScope = MutableStateFlow(StatsScope.GLOBAL)

    init {
        val threadId = savedStateHandle.get<Long>("threadId") ?: -1L
        if (threadId != -1L) preSelectThread(threadId)
    }

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

    // ── Heatmap (live, month-scoped) ──────────────────────────────────────────

    val heatmapMessages: StateFlow<List<MessageEntity>> =
        combine(_selectedThreadId, _heatmapMonth) { id, month -> id to month }
            .flatMapLatest { (id, month) ->
                val startMs = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endMs   = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (id == null) messageDao.observeMessagesInRange(startMs, endMs)
                else messageDao.observeMessagesInRangeForThread(id, startMs, endMs)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val heatmapData: StateFlow<HeatmapData> =
        combine(heatmapMessages, _heatmapMonth) { msgs, month ->
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = TimeZone.getDefault() }
            val dayLabels = (1..month.lengthOfMonth()).map { day ->
                fmt.format(Date(month.atDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()))
            }
            HeatmapData(dayLabels = dayLabels, countByDay = groupMessagesByDay(msgs))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), run {
            val month = YearMonth.now()
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = TimeZone.getDefault() }
            val dayLabels = (1..month.lengthOfMonth()).map { day ->
                fmt.format(Date(month.atDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()))
            }
            HeatmapData(dayLabels = dayLabels, countByDay = emptyMap())
        })

    /** Message count by day-of-week (Mon=0..Sun=6) for the currently displayed heatmap month
     *  and scope — used by the "By day of week" chart so it reflects the visible month. */
    val heatmapByDayOfWeek: StateFlow<IntArray> =
        heatmapMessages
            .map { msgs ->
                val result = IntArray(7)
                msgs.forEach { msg ->
                    val dow = Instant.ofEpochMilli(msg.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .dayOfWeek.value - 1  // Mon=0 .. Sun=6
                    result[dow]++
                }
                result
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntArray(7))

    val selectedDayMessages: StateFlow<List<MessageEntity>> =
        combine(heatmapMessages, _selectedHeatmapDays) { msgs, days ->
            if (days.isEmpty()) emptyList()
            else {
                val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = TimeZone.getDefault() }
                val dayStrings = days.map { it.toString() }.toSet()
                msgs.filter { msg -> fmt.format(Date(msg.timestamp)) in dayStrings }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Actions ───────────────────────────────────────────────────────────────

    fun selectThread(id: Long?) {
        if (id != null) {
            _originScope.value = _selectedScope.value
            if (_selectedScope.value != StatsScope.PER_THREAD) {
                // Carry over the current global style so the user stays in the same
                // view mode (e.g. HEATMAP) when drilling into a specific thread.
                _threadStyle.value = _globalStyle.value
                _selectedScope.value = StatsScope.PER_THREAD
            }
        } else {
            // Exiting drilldown — restore the scope we came from so back from
            // a GLOBAL-context drilldown returns to GLOBAL, not the thread list.
            _selectedScope.value = _originScope.value
        }
        _selectedThreadId.value = id
        _selectedHeatmapDays.value = emptySet()
    }

    fun setScope(scope: StatsScope) {
        if (scope == StatsScope.PER_THREAD) {
            // Carry over the current global style so the user stays in the same
            // view mode (e.g. HEATMAP) when drilling into a specific thread.
            _threadStyle.value = _globalStyle.value
        }
        _originScope.value = scope  // explicit tab switch resets the drilldown origin
        _selectedScope.value = scope
        _selectedThreadId.value = null
        _directThreadNavigation.value = false
        _selectedHeatmapDays.value = emptySet()
    }

    fun setHeatmapMonth(month: YearMonth) {
        _heatmapMonth.value = month
        _selectedHeatmapDays.value = emptySet()
    }

    /** Adds [date] to the selection if not present, removes it if already selected. */
    fun toggleHeatmapDay(date: LocalDate) {
        val current = _selectedHeatmapDays.value
        _selectedHeatmapDays.value =
            if (date in current) current - date else current + date
    }

    fun clearHeatmapDays() { _selectedHeatmapDays.value = emptySet() }

    /** Pre-select a thread (called when navigating here directly from a thread). */
    fun preSelectThread(id: Long) {
        _selectedScope.value = StatsScope.PER_THREAD
        _selectedThreadId.value = id
        _directThreadNavigation.value = true
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
