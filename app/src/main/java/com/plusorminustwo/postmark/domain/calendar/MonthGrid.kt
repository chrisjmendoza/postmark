package com.plusorminustwo.postmark.domain.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Pure calendar math behind the paged date-range picker
 * ([com.plusorminustwo.postmark.ui.components.DateRangePickerDialog]): page ↔ month
 * mapping for the horizontal pager, the day cells of one month, and how tapping a day
 * advances the selection. No Compose, no Android — all of it unit-tested.
 */

/** An inclusive day range, built up one tap at a time. A null [end] means "start only". */
data class DayRange(val start: LocalDate? = null, val end: LocalDate? = null) {
    val isComplete: Boolean get() = start != null && end != null
}

/** Where a day sits in the current [DayRange] — decides how its cell is drawn. */
enum class DayRole {
    /** Outside the selection. */
    NONE,

    /** First day of a multi-day range. */
    START,

    /** Last day of a multi-day range. */
    END,

    /** Between the endpoints. */
    MIDDLE,

    /** Both endpoints at once: a start with no end yet, or a one-day range. */
    SINGLE
}

object MonthGrid {

    /** Pager pages needed to cover [years], one page per month. */
    fun pageCount(years: IntRange): Int = years.count() * 12

    /** The month shown on [page], counting months from January of [years].first. */
    fun monthAt(page: Int, years: IntRange): YearMonth {
        val clamped = page.coerceIn(0, pageCount(years) - 1)
        return YearMonth.of(years.first + clamped / 12, clamped % 12 + 1)
    }

    /** The page showing [month], clamped into [years] so an out-of-range jump still lands. */
    fun pageOf(month: YearMonth, years: IntRange): Int {
        val year = month.year.coerceIn(years.first, years.last)
        return ((year - years.first) * 12 + month.monthValue - 1)
            .coerceIn(0, pageCount(years) - 1)
    }

    /** The seven weekday headers, rotated so the locale's [firstDayOfWeek] comes first. */
    fun weekdays(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
        (0L..6L).map { firstDayOfWeek.plus(it) }

    /**
     * The cells of [month]'s grid in reading order: leading nulls padding out to
     * [firstDayOfWeek], then every day of the month. Chunk by 7 to get weeks; the final
     * week may be short, which the UI pads with spacers.
     */
    fun cells(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate?> {
        val lead = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        return List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    }

    /**
     * Applies a day tap to [current]. One rule covers everything a user can do: a tap
     * either extends the open selection forward, or starts a fresh one. Tapping before
     * the pending start restarts there rather than making an inverted range, and tapping
     * again once a range is complete begins the next range — no "reset" button needed.
     */
    fun tap(current: DayRange, day: LocalDate): DayRange = when {
        current.start == null -> DayRange(day, null)
        current.isComplete -> DayRange(day, null)
        day < current.start -> DayRange(day, null)
        else -> DayRange(current.start, day)
    }

    /** How [day] should be drawn given [range]. */
    fun roleOf(day: LocalDate, range: DayRange): DayRole {
        val start = range.start ?: return DayRole.NONE
        val end = range.end ?: return if (day == start) DayRole.SINGLE else DayRole.NONE
        return when {
            start == end && day == start -> DayRole.SINGLE
            day == start -> DayRole.START
            day == end -> DayRole.END
            day > start && day < end -> DayRole.MIDDLE
            else -> DayRole.NONE
        }
    }
}
