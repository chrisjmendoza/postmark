package com.plusorminustwo.postmark.ui.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.MessageMeta
import com.plusorminustwo.postmark.data.db.dao.StatsDao
import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.sync.buildGlobalStatsData
import com.plusorminustwo.postmark.data.sync.buildThreadStatsData
import com.plusorminustwo.postmark.data.sync.computeResponseTimeBuckets
import com.plusorminustwo.postmark.data.sync.groupMessagesByDay
import com.plusorminustwo.postmark.data.sync.localDay
import com.plusorminustwo.postmark.data.sync.threadAggregateOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
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
 * Drives both the global aggregate view and the per-thread drilldown view from lean,
 * reactive Room projections — re-computing [ParsedStats] and [HeatmapData] whenever the
 * underlying messages or reactions change. The timezone-free counts come straight from
 * SQL GROUP BY ([StatsDao]); the zone-dependent buckets (active days, streaks,
 * day-of-week, month, response time) are derived in Kotlin over a 3-primitive
 * [MessageMeta] projection so an invalidation never materializes the full table.
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
    private val statsDao: StatsDao,
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

    // ── Live projection sources ───────────────────────────────────────────────
    //
    // Room emits on every insert/update/delete. All stats are derived from these lean
    // reactive projections — no full-entity table load, no manual refresh.

    /** Coalesces Room invalidation storms: while Stats is open during a sync or import
     *  burst, an observer would otherwise re-project every row once per write. The first
     *  emission passes through untouched so the screen isn't blank for a second on open.
     *  Runs in the shareIn collector's context (viewModelScope), so tests drive it with
     *  virtual time. */
    private fun <T> Flow<T>.debounceAfterFirst(timeoutMs: Long = 1_000L): Flow<T> =
        withIndex()
            .debounce { if (it.index == 0) 0L else timeoutMs }
            .map { it.value }

    // ── Global + per-thread stats (live, one shared pass) ────────────────────

    private data class StatsInputs(
        val threadAggregates: List<com.plusorminustwo.postmark.data.db.dao.ThreadAggregateRow>,
        val globalAggregate: com.plusorminustwo.postmark.data.db.dao.GlobalAggregateRow,
        val metas: List<MessageMeta>,
        val emojiBodies: List<com.plusorminustwo.postmark.data.db.dao.EmojiBodyRow>,
        val reactionEmojis: List<com.plusorminustwo.postmark.data.db.dao.ThreadEmojiRow>,
        val threadCount: Int
    )

    private data class StatsPayload(
        val global: ParsedStats?,
        val perThread: List<Pair<Long, ParsedStats>>
    )

    /** The five lean projections coalesced into one debounced source. The combine transform
     *  (just packing references) and the [debounceAfterFirst] run in the shareIn collector
     *  context (viewModelScope), so invalidation storms coalesce with the first emission
     *  passing through untouched — and tests drive the debounce with virtual time. */
    private val statsInputs: SharedFlow<StatsInputs> =
        combine(
            statsDao.observeThreadAggregates(),
            statsDao.observeMessageMetas(),
            statsDao.observeEmojiBodies(),
            statsDao.observeReactionEmojisByThread(),
            combine(statsDao.observeGlobalAggregates(), threadDao.observeAll()) { g, threads -> g to threads.size }
        ) { threadAggs, metas, emojiBodies, reactionEmojis, globalAndCount ->
            StatsInputs(threadAggs, globalAndCount.first, metas, emojiBodies, reactionEmojis, globalAndCount.second)
        }
            .debounceAfterFirst()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Global and per-thread stats computed in ONE transform per emission. The SQL
     *  aggregate rows supply the timezone-free counts; the meta/emoji projections supply
     *  the zone-dependent buckets and emoji. The heavy pass runs on Default and is shared
     *  so neither consumer re-triggers it. */
    private val statsPayload: SharedFlow<StatsPayload> =
        statsInputs
            .map { inputs -> computeStatsPayload(inputs) }
            .flowOn(Dispatchers.Default)
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private fun computeStatsPayload(inputs: StatsInputs): StatsPayload {
        val zone = ZoneId.systemDefault()
        val allBodies = inputs.emojiBodies.map { it.body }
        val allReactionEmojis = inputs.reactionEmojis.map { it.emoji }

        val global =
            if (inputs.globalAggregate.totalMessages == 0) null
            else buildGlobalStatsData(
                aggregate = inputs.globalAggregate,
                threadCount = inputs.threadCount,
                metas = inputs.metas,
                emojiBodies = allBodies,
                reactions = allReactionEmojis,
                zone = zone
            ).toParsed()

        val metasByThread = inputs.metas.groupBy { it.threadId }
        val bodiesByThread = inputs.emojiBodies.groupBy { it.threadId }
        val reactionsByThread = inputs.reactionEmojis.groupBy { it.threadId }

        val perThread = inputs.threadAggregates
            .map { agg ->
                agg.threadId to buildThreadStatsData(
                    aggregate = agg,
                    metas = metasByThread[agg.threadId].orEmpty(),
                    emojiBodies = bodiesByThread[agg.threadId].orEmpty().map { it.body },
                    reactions = reactionsByThread[agg.threadId].orEmpty().map { it.emoji },
                    zone = zone
                ).toParsed()
            }
            .sortedByDescending { (_, stats) -> stats.totalMessages }

        return StatsPayload(global, perThread)
    }

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

    /** Full message list for the selected thread — switches automatically. Feeds the
     *  per-thread "recent messages by day" panel, which renders message bodies (so it
     *  genuinely needs full rows), and is reused to derive the drilldown's stats. */
    val selectedThreadMessages: StateFlow<List<MessageEntity>> = _selectedThreadId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else messageDao.observeByThread(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Reaction emoji for the selected thread — switches automatically with the thread. */
    private val selectedThreadReactionEmojis: StateFlow<List<String>> = _selectedThreadId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else statsDao.observeReactionEmojisByThread().map { rows -> rows.filter { it.threadId == id }.map { it.emoji } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Stats for the selected thread, derived live from its messages and reactions.
     *  Reuses the already-loaded full rows (projected to lean metas here) instead of a
     *  second query. */
    val parsedSelectedStats: StateFlow<ParsedStats?> =
        combine(selectedThreadMessages, selectedThreadReactionEmojis) { msgs, reactionEmojis ->
            if (msgs.isEmpty()) null
            else {
                val metas = msgs.toMetas()
                buildThreadStatsData(
                    aggregate = threadAggregateOf(metas),
                    metas = metas,
                    emojiBodies = msgs.filter { it.body.isNotEmpty() }.map { it.body },
                    reactions = reactionEmojis,
                    zone = ZoneId.systemDefault()
                ).toParsed()
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [<1min, 1–5min, 5–30min, >30min] response-time distribution. */
    val responseBuckets: StateFlow<IntArray> = selectedThreadMessages
        .map { msgs ->
            if (msgs.isEmpty()) IntArray(4)
            else computeResponseTimeBuckets(msgs.toMetas().sortedBy { it.timestamp })
        }
        .flowOn(Dispatchers.Default) // full-thread sort — keep it off Main
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntArray(4))

    // ── Heatmap (live, month-scoped) ──────────────────────────────────────────

    /** Lean month-scoped metas — the heatmap grid, day-of-week chart, and top-contacts
     *  panel all need only (threadId, timestamp), never full rows. */
    val heatmapMetas: StateFlow<List<MessageMeta>> =
        combine(_selectedThreadId, _heatmapMonth) { id, month -> id to month }
            .flatMapLatest { (id, month) ->
                val startMs = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endMs   = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (id == null) statsDao.observeMessageMetasInRange(startMs, endMs)
                else statsDao.observeMessageMetasInRangeForThread(id, startMs, endMs)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val heatmapData: StateFlow<HeatmapData> =
        combine(heatmapMetas, _heatmapMonth) { metas, month ->
            // LocalDate.toString() is ISO yyyy-MM-dd — the same labels the old
            // SimpleDateFormat produced, no formatter or Date boxing needed.
            val dayLabels = (1..month.lengthOfMonth()).map { day -> month.atDay(day).toString() }
            HeatmapData(dayLabels = dayLabels, countByDay = groupMessagesByDay(metas))
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
        heatmapMetas
            .map { metas ->
                val result = IntArray(7)
                val zone = ZoneId.systemDefault()
                metas.forEach { meta ->
                    val dow = Instant.ofEpochMilli(meta.timestamp)
                        .atZone(zone)
                        .dayOfWeek.value - 1  // Mon=0 .. Sun=6
                    result[dow]++
                }
                result
            }
            // No dispatcher hop: one formatter-free pass over a single month's metas,
            // and the DoW tests collect synchronously on an unconfined dispatcher.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntArray(7))

    /** Full message rows for the currently selected day(s) — needs bodies for the detail
     *  panel, so scoped to the selected day RANGE (not the whole month) and not observed
     *  at all while nothing is selected. */
    val selectedDayMessages: StateFlow<List<MessageEntity>> =
        combine(_selectedThreadId, _heatmapSelection) { id, selection -> id to selection }
            .flatMapLatest { (id, selection) ->
                if (selection.days.isEmpty()) flowOf(emptyList())
                else {
                    val zone = ZoneId.systemDefault()
                    val startMs = selection.days.min().atStartOfDay(zone).toInstant().toEpochMilli()
                    val endMs   = selection.days.max().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val ranged =
                        if (id == null) messageDao.observeMessagesInRange(startMs, endMs)
                        else messageDao.observeMessagesInRangeForThread(id, startMs, endMs)
                    ranged.map { msgs ->
                        // Selection may be non-contiguous (multi-select) inside the range.
                        msgs.filter { localDay(it.timestamp, zone) in selection.days }
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

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun List<MessageEntity>.toMetas(): List<MessageMeta> =
        map { MessageMeta(it.threadId, it.timestamp, it.isSent) }

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
