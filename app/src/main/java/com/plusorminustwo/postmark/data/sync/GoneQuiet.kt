package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.dao.MessageMeta
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * "Gone quiet" detection — surfaces threads that have dropped significantly below their
 * usual messaging frequency for 7+ days. Pure function, no wall-clock reads: [nowMs] and
 * [zone] are injected so results are deterministic and testable.
 *
 * A thread only has a "usual frequency" worth comparing against once it has enough recent
 * history — [GONE_QUIET_MIN_HISTORY_MESSAGES] messages within the
 * [GONE_QUIET_HISTORY_WINDOW_DAYS] days before [nowMs]. The MEDIAN (not mean) inter-message
 * gap over that window is used as the "usual" cadence — a mean would let a single reply
 * burst compress the average gap and make a thread look busier than it really is.
 */

/** How far back to look for "usual frequency" history. */
internal const val GONE_QUIET_HISTORY_WINDOW_DAYS = 90L

/** Minimum messages within the window to trust a "usual gap" — below this there's not
 *  enough history to say a thread has gone quiet vs. simply being new. */
internal const val GONE_QUIET_MIN_HISTORY_MESSAGES = 20

/** Minimum days since the last message before a thread is even considered. */
internal const val GONE_QUIET_MIN_DAYS_QUIET = 7

/** A thread must be quiet for at least this many multiples of its usual gap. */
internal const val GONE_QUIET_GAP_MULTIPLIER = 4.0

/** Floor for the "usual gap" — an all-same-day (bursty) history has a median gap of ~0
 *  days, which would make the multiplier threshold in [GONE_QUIET_GAP_MULTIPLIER] trivially
 *  satisfied by any handful of quiet days. Flooring at 1 day keeps the comparison meaningful. */
internal const val GONE_QUIET_MIN_GAP_DAYS = 1.0

/** Default number of threads surfaced in the "Gone quiet" card. */
internal const val GONE_QUIET_DEFAULT_LIMIT = 3

/** One thread that has gone quiet relative to its own usual cadence. */
internal data class GoneQuietThread(
    val threadId: Long,
    /** Calendar days since the thread's last message, in the injected [ZoneId]. */
    val daysQuiet: Int,
    /** The thread's usual inter-message gap in days (median over the history window,
     *  floored at [GONE_QUIET_MIN_GAP_DAYS] and rounded for display). */
    val usualGapDays: Int
)

/**
 * Detects threads that have gone quiet — see the class doc above for the full definition.
 * [metasByThread] should be the same per-thread grouping the Stats payload already computes
 * (no separate DB read). Returns at most [limit] threads, sorted by [GoneQuietThread.daysQuiet]
 * descending (the longest-silent thread first).
 */
internal fun detectGoneQuiet(
    metasByThread: Map<Long, List<MessageMeta>>,
    nowMs: Long,
    zone: ZoneId,
    limit: Int = GONE_QUIET_DEFAULT_LIMIT
): List<GoneQuietThread> {
    val windowStartMs = nowMs - GONE_QUIET_HISTORY_WINDOW_DAYS * 24 * 3600_000L
    val nowDay = localDay(nowMs, zone)

    return metasByThread.mapNotNull { (threadId, metas) ->
        val windowMetas = metas.filter { it.timestamp in windowStartMs..nowMs }
        if (windowMetas.size < GONE_QUIET_MIN_HISTORY_MESSAGES) return@mapNotNull null

        val sorted = windowMetas.sortedBy { it.timestamp }
        val lastMessageAt = sorted.last().timestamp
        val daysQuiet = ChronoUnit.DAYS.between(localDay(lastMessageAt, zone), nowDay).toInt()
        if (daysQuiet < GONE_QUIET_MIN_DAYS_QUIET) return@mapNotNull null

        val gapsMs = (1 until sorted.size).map { i -> sorted[i].timestamp - sorted[i - 1].timestamp }
        val medianGapDays = (medianOf(gapsMs) / 86_400_000.0).coerceAtLeast(GONE_QUIET_MIN_GAP_DAYS)
        if (daysQuiet < GONE_QUIET_GAP_MULTIPLIER * medianGapDays) return@mapNotNull null

        GoneQuietThread(
            threadId = threadId,
            daysQuiet = daysQuiet,
            usualGapDays = medianGapDays.roundToInt().coerceAtLeast(1)
        )
    }
        .sortedByDescending { it.daysQuiet }
        .take(limit)
}

/** Median of a non-empty list of millisecond gaps — average of the two middle values on
 *  an even-sized list, the middle value on an odd-sized one. */
private fun medianOf(values: List<Long>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0
    else sorted[mid].toDouble()
}
