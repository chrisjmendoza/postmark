package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.dao.GlobalAggregateRow
import com.plusorminustwo.postmark.data.db.dao.MessageMeta
import com.plusorminustwo.postmark.data.db.dao.ThreadAggregateRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** 24 hours — response gaps beyond this are considered dormant and excluded from avg. */
internal const val MAX_RESPONSE_GAP_MS = 24L * 3600_000L

/**
 * Pure thread statistics — no JSON, no Room dependencies.
 * Produced by [buildThreadStatsData] and consumed live by the Stats screen ViewModel.
 */
internal data class ThreadStatsData(
    val totalMessages: Int = 0,
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    val firstMessageAt: Long = 0L,
    val lastMessageAt: Long = 0L,
    val activeDayCount: Int = 0,
    val longestStreakDays: Int = 0,
    val avgResponseTimeMs: Long = 0L,
    /** Top 6 emoji → count from message bodies, sorted descending. */
    val topEmojis: Map<String, Int> = emptyMap(),
    /** Top 6 emoji → count from reactions (kept separate from message emoji). */
    val topReactionEmojis: Map<String, Int> = emptyMap(),
    /** Index 0 = Monday … 6 = Sunday. */
    val byDayOfWeek: IntArray = IntArray(7),
    /** Index 0 = January … 11 = December. */
    val byMonth: IntArray = IntArray(12)
)

/**
 * Pure global statistics — aggregated across all threads.
 */
internal data class GlobalStatsData(
    val totalMessages: Int = 0,
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    val threadCount: Int = 0,
    val activeDayCount: Int = 0,
    val longestStreakDays: Int = 0,
    val avgResponseTimeMs: Long = 0L,
    /** Top 6 emoji → count from message bodies, sorted descending. */
    val topEmojis: Map<String, Int> = emptyMap(),
    /** Top 6 emoji → count from reactions (kept separate from message emoji). */
    val topReactionEmojis: Map<String, Int> = emptyMap(),
    val byDayOfWeek: IntArray = IntArray(7),
    val byMonth: IntArray = IntArray(12)
)

// ── Timezone-free aggregate mirrors ─────────────────────────────────────────────
//
// The Stats ViewModel gets these from StatsDao's SQL GROUP BY at runtime. These Kotlin
// mirrors compute the identical values from an in-memory meta list — used by the
// per-thread drilldown (which already holds the selected thread's rows) and as the
// oracle in the unit + instrumented parity tests.

internal fun threadAggregateOf(metas: List<MessageMeta>): ThreadAggregateRow =
    ThreadAggregateRow(
        threadId       = metas.firstOrNull()?.threadId ?: 0L,
        totalMessages  = metas.size,
        sentCount      = metas.count { it.isSent },
        firstMessageAt = metas.minOfOrNull { it.timestamp } ?: 0L,
        lastMessageAt  = metas.maxOfOrNull { it.timestamp } ?: 0L
    )

internal fun globalAggregateOf(metas: List<MessageMeta>): GlobalAggregateRow =
    GlobalAggregateRow(
        totalMessages  = metas.size,
        sentCount      = metas.count { it.isSent },
        firstMessageAt = metas.minOfOrNull { it.timestamp } ?: 0L,
        lastMessageAt  = metas.maxOfOrNull { it.timestamp } ?: 0L
    )

// ── Core computation ──────────────────────────────────────────────────────────

/**
 * Count emoji strings (already-extracted) and return the top [limit] by frequency.
 * Input is a flat list of emoji strings — no text extraction needed for reactions.
 */
internal fun countReactionEmojis(reactions: List<String>, limit: Int = 6): Map<String, Int> {
    if (reactions.isEmpty()) return emptyMap()
    return reactions.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .take(limit).associate { it.key to it.value }
}

/** Extract and count emoji across a list of message bodies, top [limit] descending. */
internal fun countBodyEmojis(bodies: List<String>, limit: Int = 6): Map<String, Int> {
    if (bodies.isEmpty()) return emptyMap()
    val counts = mutableMapOf<String, Int>()
    bodies.forEach { body -> extractEmojis(body).forEach { e -> counts[e] = (counts[e] ?: 0) + 1 } }
    return counts.entries.sortedByDescending { it.value }.take(limit).associate { it.key to it.value }
}

/**
 * Assemble a thread's stats from the SQL [aggregate] (timezone-free counts) plus the
 * zone-dependent buckets derived in Kotlin over [metas] and the emoji from [emojiBodies].
 * [zone] defaults to the device zone at every call site — the same boundary the UI uses.
 */
internal fun buildThreadStatsData(
    aggregate: ThreadAggregateRow,
    metas: List<MessageMeta>,
    emojiBodies: List<String> = emptyList(),
    reactions: List<String> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault()
): ThreadStatsData {
    if (aggregate.totalMessages == 0) return ThreadStatsData()
    val sorted = metas.sortedBy { it.timestamp }
    val (activeDays, dowArray, monthArray) = dayBuckets(sorted, zone)

    return ThreadStatsData(
        totalMessages     = aggregate.totalMessages,
        sentCount         = aggregate.sentCount,
        receivedCount     = aggregate.totalMessages - aggregate.sentCount,
        firstMessageAt    = aggregate.firstMessageAt,
        lastMessageAt     = aggregate.lastMessageAt,
        activeDayCount    = activeDays.size,
        longestStreakDays = computeLongestStreak(activeDays),
        avgResponseTimeMs = computeAvgResponseTimeMs(sorted),
        topEmojis         = countBodyEmojis(emojiBodies),
        topReactionEmojis = countReactionEmojis(reactions),
        byDayOfWeek       = dowArray,
        byMonth           = monthArray
    )
}

internal fun buildGlobalStatsData(
    aggregate: GlobalAggregateRow,
    threadCount: Int,
    metas: List<MessageMeta>,
    emojiBodies: List<String> = emptyList(),
    reactions: List<String> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault()
): GlobalStatsData {
    if (aggregate.totalMessages == 0) return GlobalStatsData(threadCount = threadCount)
    val sorted = metas.sortedBy { it.timestamp }
    val (activeDays, dowArray, monthArray) = dayBuckets(sorted, zone)

    // Global avg response time: per-thread averages weighted by message count. Can't be
    // computed cleanly from pre-aggregated stats, so approximate by grouping metas by
    // threadId and averaging per-thread results.
    var totalAvgNumerator = 0L
    var totalWeight = 0
    sorted.groupBy { it.threadId }.values.forEach { threadMetas ->
        val avg = computeAvgResponseTimeMs(threadMetas.sortedBy { it.timestamp })
        if (avg > 0) {
            totalAvgNumerator += avg * threadMetas.size
            totalWeight += threadMetas.size
        }
    }
    val globalAvg = if (totalWeight > 0) totalAvgNumerator / totalWeight else 0L

    return GlobalStatsData(
        totalMessages     = aggregate.totalMessages,
        sentCount         = aggregate.sentCount,
        receivedCount     = aggregate.totalMessages - aggregate.sentCount,
        threadCount       = threadCount,
        activeDayCount    = activeDays.size,
        longestStreakDays = computeLongestStreak(activeDays),
        avgResponseTimeMs = globalAvg,
        topEmojis         = countBodyEmojis(emojiBodies),
        topReactionEmojis = countReactionEmojis(reactions),
        byDayOfWeek       = dowArray,
        byMonth           = monthArray
    )
}

/**
 * Single pass over sorted [metas] producing the three zone-dependent buckets:
 * the distinct sorted "yyyy-MM-dd" active-day labels, the Mon=0..Sun=6 day-of-week
 * histogram, and the Jan=0..Dec=11 month histogram — all in [zone].
 */
private fun dayBuckets(sorted: List<MessageMeta>, zone: ZoneId): Triple<List<String>, IntArray, IntArray> {
    val dowArray = IntArray(7)
    val monthArray = IntArray(12)
    val days = LinkedHashSet<String>()
    sorted.forEach { m ->
        val zdt = Instant.ofEpochMilli(m.timestamp).atZone(zone)
        days.add(zdt.toLocalDate().toString())
        dowArray[(zdt.dayOfWeek.value + 6) % 7]++   // ISO Mon=1..Sun=7 → Mon=0..Sun=6
        monthArray[zdt.monthValue - 1]++            // ISO Jan=1..Dec=12 → 0..11
    }
    return Triple(days.toList().sorted(), dowArray, monthArray)
}

// ── Individual algorithms ─────────────────────────────────────────────────────

/**
 * Longest run of consecutive calendar days (local time) in a sorted-ascending
 * list of "yyyy-MM-dd" strings. Uses [java.time.LocalDate] to avoid DST errors.
 */
internal fun computeLongestStreak(sortedDays: List<String>): Int {
    if (sortedDays.isEmpty()) return 0
    var maxStreak = 1
    var current = 1
    for (i in 1 until sortedDays.size) {
        val prev = LocalDate.parse(sortedDays[i - 1])
        val curr = LocalDate.parse(sortedDays[i])
        if (ChronoUnit.DAYS.between(prev, curr) == 1L) {
            if (++current > maxStreak) maxStreak = current
        } else {
            current = 1
        }
    }
    return maxStreak
}

/**
 * Average time between direction-changes in a sorted message list.
 * Gaps over [maxGapMs] (default 24 h) are excluded so dormant threads
 * don't inflate the average. Operates on lean [MessageMeta] (timestamp + isSent).
 */
internal fun computeAvgResponseTimeMs(
    sorted: List<MessageMeta>,
    maxGapMs: Long = MAX_RESPONSE_GAP_MS
): Long {
    var total = 0L
    var count = 0
    for (i in 1 until sorted.size) {
        val gap = sorted[i].timestamp - sorted[i - 1].timestamp
        if (sorted[i - 1].isSent != sorted[i].isSent && gap in 1..maxGapMs) {
            total += gap
            count++
        }
    }
    return if (count > 0) total / count else 0L
}

/**
 * Compute response-time bucket distribution for a single thread's messages.
 * Returns IntArray(4): [<1 min, 1–5 min, 5–30 min, >30 min], counts (not percentages).
 */
internal fun computeResponseTimeBuckets(
    sorted: List<MessageMeta>,
    maxGapMs: Long = MAX_RESPONSE_GAP_MS
): IntArray {
    val buckets = IntArray(4)
    for (i in 1 until sorted.size) {
        val gap = sorted[i].timestamp - sorted[i - 1].timestamp
        if (sorted[i - 1].isSent != sorted[i].isSent && gap in 1..maxGapMs) {
            buckets[when {
                gap < 60_000L         -> 0  // < 1 min
                gap < 5 * 60_000L     -> 1  // 1–5 min
                gap < 30 * 60_000L    -> 2  // 5–30 min
                else                  -> 3  // > 30 min
            }]++
        }
    }
    return buckets
}

/**
 * Extract Unicode emoji codepoints from [text] using the spec regex pattern.
 * Handles surrogate pairs (SMP emoji) and BMP symbol/Misc ranges.
 */
internal fun extractEmojis(text: String): List<String> {
    val result = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (isEmojiCodePoint(cp)) result.add(String(Character.toChars(cp)))
        i += Character.charCount(cp)
    }
    return result
}

internal fun isEmojiCodePoint(cp: Int): Boolean =
    cp in 0x1F000..0x1FAFF ||
    cp in 0x2600..0x27BF  ||
    cp in 0x2300..0x23FF  ||
    cp in 0x2B00..0x2BFF

/**
 * Map a raw message count for one heatmap cell to an intensity tier 0–6,
 * scaled relative to the busiest day in view (maxCount). Tier 6 is always
 * the day(s) with maxCount messages; tier 0 is always zero messages.
 */
internal fun heatmapTierForCount(count: Int, maxCount: Int): Int {
    if (count <= 0 || maxCount <= 0) return 0
    return ceil(count.toFloat() / maxCount.toFloat() * 6).toInt().coerceIn(1, 6)
}

/** The local calendar day of [timestampMs] in [zone] — the single day-bucket boundary
 *  shared by the heatmap, the streak/active-day math, and the day-panel grouping so all
 *  of them agree on which day a near-midnight message belongs to. */
internal fun localDay(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()

/** Groups message timestamps into a "yyyy-MM-dd" → count map using local-timezone days. */
internal fun groupMessagesByDay(
    metas: List<MessageMeta>,
    zone: ZoneId = ZoneId.systemDefault()
): Map<String, Int> =
    metas.groupingBy { localDay(it.timestamp, zone).toString() }.eachCount()
