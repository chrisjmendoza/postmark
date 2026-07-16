package com.plusorminustwo.postmark.ui.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ReactionEntity
import com.plusorminustwo.postmark.data.sync.buildGlobalStatsData
import com.plusorminustwo.postmark.data.sync.buildThreadStatsData
import com.plusorminustwo.postmark.data.sync.computeResponseTimeBuckets
import com.plusorminustwo.postmark.data.sync.groupMessagesByDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** Controls how statistics are rendered: as numbers, bar charts, or a heatmap grid. */
enum class StatsDisplayStyle { NUMBERS, CHARTS, HEATMAP }

/** Whether the Stats screen is showing aggregate data or data for a single thread. */
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
    /** Top 6 (emoji, count) pairs from message bodies, sorted descending. */
    val topEmojis: List<Pair<String, Int>> = emptyList(),
    /** Top 6 (emoji, count) pairs from reactions, sorted descending. Tracked separately. */
    val topReactionEmojis: List<Pair<String, Int>> = emptyList(),
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

/**
 * ViewModel for the Stats screen.
 *
 * Drives both the global aggregate view and the per-thread drilldown view from a
 * single reactive Room query, re-computing [ParsedStats] and [HeatmapData] whenever
 * the underlying messages or reactions change.
 *
 * The [selectedScope] / [selectedThreadId] pair controls which data is displayed.
 * When launched with a `threadId` in [SavedStateHandle] (from a thread overflow menu),
 * [directThreadNavigation] is `true` so the back button returns to the thread instead
 * of the thread-selection list.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val threadDao: ThreadDao,
    private val messageDao: MessageDao,
    private val reactionDao: ReactionDao,
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

    // ── Heatmap month navigation ──────────────────────────────────────────────

    private val _heatmapMonth = MutableStateFlow(YearMonth.now())
    val heatmapMonth: StateFlow<YearMonth> = _heatmapMonth

    private val _heatmapSelection = MutableStateFlow(HeatmapSelection())
    val heatmapSelection: StateFlow<HeatmapSelection> = _heatmapSelection

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

    /** Coalesces Room invalidation storms: while Stats is open during a sync or
     *  import burst, a full-table observer would otherwise re-materialize every
     *  row once per write. The first emission passes through untouched so the
     *  screen isn't blank for a second on open. Runs in the shareIn collector's
     *  context (viewModelScope), so tests drive it with virtual time. */
    private fun <T> Flow<T>.debounceAfterFirst(timeoutMs: Long = 1_000L): Flow<T> =
        withIndex()
            .debounce { if (it.index == 0) 0L else timeoutMs }
            .map { it.value }

    private val allMessages: SharedFlow<List<MessageEntity>> =
        messageDao.observeMessagesFrom(0L)
            .debounceAfterFirst()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val allReactions: SharedFlow<List<ReactionEntity>> =
        reactionDao.observeAll()
            .debounceAfterFirst()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // ── Global + per-thread stats (live, one shared pass) ────────────────────

    private data class StatsPayload(
        val global: ParsedStats?,
        val perThread: List<Pair<Long, ParsedStats>>
    )

    /** Global and per-thread stats computed in ONE transform per emission —
     *  previously two independent combines each re-walked all messages on every
     *  invalidation. Shared on Default so neither consumer re-triggers the pass. */
    private val statsPayload: SharedFlow<StatsPayload> =
        combine(allMessages, allReactions, threadDao.observeAll()) { msgs, reactions, threads ->
            val global =
                if (msgs.isEmpty()) null
                else buildGlobalStatsData(msgs, threads.size, reactions.map { it.emoji }).toParsed()
            val msgToThread = msgs.associate { it.id to it.threadId }
            val reactionsByThread = reactions
                .groupBy { r -> msgToThread[r.messageId] ?: -1L }
                .filterKeys { it != -1L }
            val perThread = msgs.groupBy { it.threadId }
                .map { (threadId, threadMsgs) ->
                    val threadReactionEmojis = reactionsByThread[threadId]?.map { it.emoji } ?: emptyList()
                    threadId to buildThreadStatsData(threadMsgs, threadReactionEmojis).toParsed()
                }
                .sortedByDescending { (_, stats) -> stats.totalMessages }
            StatsPayload(global, perThread)
        }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Null until Room delivers the first emission (typically < 50 ms after
     * subscription). Null also means "no messages yet".
     */
    val parsedGlobalStats: StateFlow<ParsedStats?> = statsPayload
        .map { it.global }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Per-thread stats sorted by total message count, recomputed live whenever
     * any message changes.
     */
    val allLiveThreadStats: StateFlow<List<Pair<Long, ParsedStats>>> = statsPayload
        .map { it.perThread }
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

    /** Reactions for the selected thread — switches automatically with the thread. */
    private val selectedThreadReactions: StateFlow<List<ReactionEntity>> = _selectedThreadId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else reactionDao.observeByThread(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Stats for the selected thread, derived live from its messages and reactions. */
    val parsedSelectedStats: StateFlow<ParsedStats?> =
        combine(selectedThreadMessages, selectedThreadReactions) { msgs, reactions ->
            if (msgs.isEmpty()) null
            else buildThreadStatsData(msgs, reactions.map { it.emoji }).toParsed()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [<1min, 1–5min, 5–30min, >30min] response-time distribution. */
    val responseBuckets: StateFlow<IntArray> = selectedThreadMessages
        .map { msgs ->
            if (msgs.isEmpty()) IntArray(4)
            else computeResponseTimeBuckets(msgs.sortedBy { it.timestamp })
        }
        .flowOn(Dispatchers.Default) // full-thread sort — keep it off Main
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
            // LocalDate.toString() is ISO yyyy-MM-dd — same labels the old
            // SimpleDateFormat produced, no formatter or Date boxing needed.
            val dayLabels = (1..month.lengthOfMonth()).map { day -> month.atDay(day).toString() }
            HeatmapData(dayLabels = dayLabels, countByDay = groupMessagesByDay(msgs))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), run {
            val month = YearMonth.now()
            HeatmapData(
                dayLabels = (1..month.lengthOfMonth()).map { day -> month.atDay(day).toString() },
                countByDay = emptyMap()
            )
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
            // No dispatcher hop: one formatter-free pass over a single month's
            // messages (the full-table work runs on Default upstream), and the
            // DoW tests collect synchronously on an unconfined dispatcher.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntArray(7))

    val selectedDayMessages: StateFlow<List<MessageEntity>> =
        combine(heatmapMessages, _heatmapSelection) { msgs, selection ->
            if (selection.days.isEmpty()) emptyList()
            else {
                val zone = ZoneId.systemDefault()
                msgs.filter { msg ->
                    Instant.ofEpochMilli(msg.timestamp).atZone(zone).toLocalDate() in selection.days
                }
            }
        }
        .flowOn(Dispatchers.Default)
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
        _heatmapSelection.value = HeatmapSelection()
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
        _heatmapSelection.value = HeatmapSelection()
    }

    fun setHeatmapMonth(month: YearMonth) {
        _heatmapMonth.value = month
        _heatmapSelection.value = HeatmapSelection()
    }

    /** Tap: selects just [date] — or toggles it while in multi-select. See [HeatmapSelection]. */
    fun tapHeatmapDay(date: LocalDate) {
        _heatmapSelection.value = _heatmapSelection.value.tap(date)
    }

    /** Long-press: enters multi-select / extends a range to [date]. See [HeatmapSelection]. */
    fun longPressHeatmapDay(date: LocalDate) {
        _heatmapSelection.value = _heatmapSelection.value.longPress(date)
    }

    fun clearHeatmapDays() { _heatmapSelection.value = HeatmapSelection() }

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

    // ── Data class → ParsedStats ──────────────────────────────────────────────

    private fun com.plusorminustwo.postmark.data.sync.ThreadStatsData.toParsed() = ParsedStats(
        totalMessages     = totalMessages,
        sentCount         = sentCount,
        receivedCount     = receivedCount,
        activeDayCount    = activeDayCount,
        longestStreakDays  = longestStreakDays,
        avgResponseTimeMs = avgResponseTimeMs,
        topEmojis         = topEmojis.entries.map { it.key to it.value },
        topReactionEmojis = topReactionEmojis.entries.map { it.key to it.value },
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
        topReactionEmojis = topReactionEmojis.entries.map { it.key to it.value },
        byDayOfWeek       = byDayOfWeek,
        byMonth           = byMonth,
        threadCount       = threadCount
    )
}
