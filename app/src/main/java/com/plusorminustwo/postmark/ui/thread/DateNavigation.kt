package com.plusorminustwo.postmark.ui.thread

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Returns the date in [activeDates] closest to [target] by number of days.
 * If two dates are equidistant, the earlier one is returned (minByOrNull tiebreaker).
 * Returns null if [activeDates] is empty.
 */
fun findNearestActiveDate(target: LocalDate, activeDates: Set<LocalDate>): LocalDate? {
    if (activeDates.isEmpty()) return null
    return activeDates.minByOrNull { abs(ChronoUnit.DAYS.between(it, target)) }
}
