package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

internal fun buildThreadStatsData(
    messages: List<MessageEntity>,
    reactions: List<String> = emptyList()
): ThreadStatsData {
    if (messages.isEmpty()) return ThreadStatsData()
    val sorted = messages.sortedBy { it.timestamp }

    val dayFmt = localDayFormatter()
    val activeDays = sorted.map { dayFmt.format(Date(it.timestamp)) }.distinct().sorted()

    val emojiCounts = mutableMapOf<String, Int>()
    val dowArray = IntArray(7)
    val monthArray = IntArray(12)
    val cal = Calendar.getInstance()

    sorted.forEach { msg ->
        extractEmojis(msg.body).forEach { e -> emojiCounts[e] = (emojiCounts[e] ?: 0) + 1 }
        cal.timeInMillis = msg.timestamp
        dowArray[(cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7]++
        monthArray[cal.get(Calendar.MONTH)]++
    }

    return ThreadStatsData(
        totalMessages     = sorted.size,
        sentCount         = sorted.count { it.isSent },
        receivedCount     = sorted.count { !it.isSent },
        firstMessageAt    = sorted.first().timestamp,
        lastMessageAt     = sorted.last().timestamp,
        activeDayCount    = activeDays.size,
        longestStreakDays  = computeLongestStreak(activeDays),
        avgResponseTimeMs = computeAvgResponseTimeMs(sorted),
        topEmojis         = emojiCounts.entries.sortedByDescending { it.value }
                                .take(6).associate { it.key to it.value },
        topReactionEmojis = countReactionEmojis(reactions),
        byDayOfWeek       = dowArray,
        byMonth           = monthArray
    )
}

internal fun buildGlobalStatsData(
    allMessages: List<MessageEntity>,
    threadCount: Int,
    reactions: List<String> = emptyList()
): GlobalStatsData {
    if (allMessages.isEmpty()) return GlobalStatsData(threadCount = threadCount)
    val sorted = allMessages.sortedBy { it.timestamp }

    val dayFmt = localDayFormatter()
    val activeDays = sorted.map { dayFmt.format(Date(it.timestamp)) }.distinct().sorted()

    val emojiCounts = mutableMapOf<String, Int>()
    val dowArray = IntArray(7)
    val monthArray = IntArray(12)
    val cal = Calendar.getInstance()

    sorted.forEach { msg ->
        extractEmojis(msg.body).forEach { e -> emojiCounts[e] = (emojiCounts[e] ?: 0) + 1 }
        cal.timeInMillis = msg.timestamp
        dowArray[(cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7]++
        monthArray[cal.get(Calendar.MONTH)]++
    }

    // Global avg response time: per-thread averages weighted by message count.
    // We can't compute this cleanly from pre-aggregated stats alone, so we approximate
    // by grouping the global message list by threadId and averaging per-thread results.
    val threadGroups = sorted.groupBy { it.threadId }
    var totalAvgNumerator = 0L
    var totalWeight = 0
    threadGroups.values.forEach { msgs ->
        val avg = computeAvgResponseTimeMs(msgs.sortedBy { it.timestamp })
        if (avg > 0) {
            totalAvgNumerator += avg * msgs.size
            totalWeight += msgs.size
        }
    }
    val globalAvg = if (totalWeight > 0) totalAvgNumerator / totalWeight else 0L

    return GlobalStatsData(
        totalMessages     = sorted.size,
        sentCount         = sorted.count { it.isSent },
        receivedCount     = sorted.count { !it.isSent },
        threadCount       = threadCount,
        activeDayCount    = activeDays.size,
        longestStreakDays  = computeLongestStreak(activeDays),
        avgResponseTimeMs = globalAvg,
        topEmojis         = emojiCounts.entries.sortedByDescending { it.value }
                                .take(6).associate { it.key to it.value },
        topReactionEmojis = countReactionEmojis(reactions),
        byDayOfWeek       = dowArray,
        byMonth           = monthArray
    )
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
        val prev = java.time.LocalDate.parse(sortedDays[i - 1])
        val curr = java.time.LocalDate.parse(sortedDays[i])
        if (java.time.temporal.ChronoUnit.DAYS.between(prev, curr) == 1L) {
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
 * don't inflate the average.
 */
internal fun computeAvgResponseTimeMs(
    sorted: List<MessageEntity>,
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
    sorted: List<MessageEntity>,
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

/** Groups messages into a date→count map using local-timezone day boundaries. */
internal fun groupMessagesByDay(messages: List<MessageEntity>): Map<String, Int> {
    val fmt = localDayFormatter()
    return messages.groupingBy { fmt.format(Date(it.timestamp)) }.eachCount()
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun localDayFormatter() =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = TimeZone.getDefault() }
