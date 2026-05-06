package com.plusorminustwo.postmark.domain.model

import java.util.Calendar

/** Date-range filter options available on the Search screen. */
enum class SearchDateRange { ALL_TIME, TODAY, LAST_7_DAYS, LAST_30_DAYS }

/**
 * Converts a [SearchDateRange] to a (startMs, endMs) pair relative to [nowMs].
 *
 * - [SearchDateRange.ALL_TIME] → (null, null) — no bounds applied.
 * - [SearchDateRange.TODAY] → start of the current local day through now.
 * - [SearchDateRange.LAST_7_DAYS] → nowMs minus 7 days, open-ended.
 * - [SearchDateRange.LAST_30_DAYS] → nowMs minus 30 days, open-ended.
 *
 * The endMs component is always null (no upper bound — up to now).
 */
fun SearchDateRange.toBoundsMs(nowMs: Long = System.currentTimeMillis()): Pair<Long?, Long?> =
    when (this) {
        SearchDateRange.ALL_TIME -> null to null
        SearchDateRange.TODAY -> {
            val cal = Calendar.getInstance()
            cal.timeInMillis = nowMs
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis to null
        }
        SearchDateRange.LAST_7_DAYS -> (nowMs - 7L * 24L * 3_600_000L) to null
        SearchDateRange.LAST_30_DAYS -> (nowMs - 30L * 24L * 3_600_000L) to null
    }
