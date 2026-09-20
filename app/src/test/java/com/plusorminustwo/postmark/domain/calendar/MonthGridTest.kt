package com.plusorminustwo.postmark.domain.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Unit tests for [MonthGrid] — the pure math behind the paged date-range picker.
 * Covers the pager's page ↔ month mapping, a month's day-cell layout, the tap rules
 * that build a range, and the role each day renders as.
 */
class MonthGridTest {

    private val years = 1990..2027

    // ── Pager pages ───────────────────────────────────────────────────────────

    @Test
    fun `page count is twelve months per year`() {
        assertEquals(38 * 12, MonthGrid.pageCount(years))
    }

    @Test
    fun `page zero is January of the first year`() {
        assertEquals(YearMonth.of(1990, 1), MonthGrid.monthAt(0, years))
    }

    @Test
    fun `last page is December of the last year`() {
        val last = MonthGrid.pageCount(years) - 1
        assertEquals(YearMonth.of(2027, 12), MonthGrid.monthAt(last, years))
    }

    @Test
    fun `page and month round-trip`() {
        val month = YearMonth.of(2026, 9)
        assertEquals(month, MonthGrid.monthAt(MonthGrid.pageOf(month, years), years))
    }

    @Test
    fun `months outside the range clamp to the nearest year, keeping the month`() {
        assertEquals(
            YearMonth.of(1990, 5),
            MonthGrid.monthAt(MonthGrid.pageOf(YearMonth.of(1970, 5), years), years)
        )
        assertEquals(
            YearMonth.of(2027, 5),
            MonthGrid.monthAt(MonthGrid.pageOf(YearMonth.of(2099, 5), years), years)
        )
    }

    @Test
    fun `pages outside the range clamp to an edge month`() {
        assertEquals(YearMonth.of(1990, 1), MonthGrid.monthAt(-3, years))
        assertEquals(YearMonth.of(2027, 12), MonthGrid.monthAt(99_999, years))
    }

    // ── Day grid ──────────────────────────────────────────────────────────────

    @Test
    fun `weekday headers start at the locale first day`() {
        assertEquals(DayOfWeek.SUNDAY, MonthGrid.weekdays(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, MonthGrid.weekdays(DayOfWeek.SUNDAY).last())
        assertEquals(DayOfWeek.MONDAY, MonthGrid.weekdays(DayOfWeek.MONDAY).first())
        assertEquals(7, MonthGrid.weekdays(DayOfWeek.MONDAY).distinct().size)
    }

    @Test
    fun `cells pad the lead-in days and then run the whole month`() {
        // September 2026 starts on a Tuesday.
        val cells = MonthGrid.cells(YearMonth.of(2026, 9), DayOfWeek.SUNDAY)
        assertEquals(2 + 30, cells.size)
        assertNull(cells[0])
        assertNull(cells[1])
        assertEquals(LocalDate.of(2026, 9, 1), cells[2])
        assertEquals(LocalDate.of(2026, 9, 30), cells.last())
    }

    @Test
    fun `a month starting on the first day of week has no padding`() {
        // February 2026 starts on a Sunday.
        val cells = MonthGrid.cells(YearMonth.of(2026, 2), DayOfWeek.SUNDAY)
        assertEquals(28, cells.size)
        assertEquals(LocalDate.of(2026, 2, 1), cells.first())
    }

    @Test
    fun `padding follows the locale first day of week`() {
        // Same month, Monday-first: Tuesday the 1st needs one blank, not two.
        val cells = MonthGrid.cells(YearMonth.of(2026, 9), DayOfWeek.MONDAY)
        assertEquals(1 + 30, cells.size)
        assertEquals(LocalDate.of(2026, 9, 1), cells[1])
    }

    @Test
    fun `leap day is present in February 2028`() {
        val cells = MonthGrid.cells(YearMonth.of(2028, 2), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2028, 2, 29), cells.last())
    }

    @Test
    fun `no month needs more than six week rows`() {
        val overflowing = (1990..2030).flatMap { year -> (1..12).map { YearMonth.of(year, it) } }
            .filter { month ->
                listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.SATURDAY).any { first ->
                    MonthGrid.cells(month, first).chunked(7).size > 6
                }
            }
        assertEquals(emptyList<YearMonth>(), overflowing)
    }

    // ── Tap rules ─────────────────────────────────────────────────────────────

    @Test
    fun `first tap sets the start and leaves the end open`() {
        val day = LocalDate.of(2026, 5, 10)
        assertEquals(DayRange(day, null), MonthGrid.tap(DayRange(), day))
    }

    @Test
    fun `a later tap completes the range`() {
        val start = LocalDate.of(2026, 5, 10)
        val end = LocalDate.of(2026, 5, 20)
        assertEquals(DayRange(start, end), MonthGrid.tap(DayRange(start, null), end))
    }

    @Test
    fun `tapping the same day twice makes a one-day range`() {
        val day = LocalDate.of(2026, 5, 10)
        assertEquals(DayRange(day, day), MonthGrid.tap(DayRange(day, null), day))
    }

    @Test
    fun `tapping before the pending start restarts there instead of inverting`() {
        val start = LocalDate.of(2026, 5, 10)
        val earlier = LocalDate.of(2026, 4, 2)
        assertEquals(DayRange(earlier, null), MonthGrid.tap(DayRange(start, null), earlier))
    }

    @Test
    fun `tapping after a complete range begins a new one`() {
        val complete = DayRange(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 20))
        val next = LocalDate.of(2026, 6, 1)
        assertEquals(DayRange(next, null), MonthGrid.tap(complete, next))
    }

    @Test
    fun `a range can span months and years`() {
        val start = LocalDate.of(2019, 11, 3)
        val end = LocalDate.of(2026, 1, 8)
        val result = MonthGrid.tap(MonthGrid.tap(DayRange(), start), end)
        assertEquals(DayRange(start, end), result)
        assertEquals(true, result.isComplete)
    }

    // ── Day roles ─────────────────────────────────────────────────────────────

    @Test
    fun `nothing is selected when the range is empty`() {
        assertEquals(DayRole.NONE, MonthGrid.roleOf(LocalDate.of(2026, 5, 10), DayRange()))
    }

    @Test
    fun `a pending start renders as a single endpoint`() {
        val start = LocalDate.of(2026, 5, 10)
        val range = DayRange(start, null)
        assertEquals(DayRole.SINGLE, MonthGrid.roleOf(start, range))
        assertEquals(DayRole.NONE, MonthGrid.roleOf(start.plusDays(1), range))
    }

    @Test
    fun `a complete range marks endpoints and the days between`() {
        val start = LocalDate.of(2026, 5, 10)
        val end = LocalDate.of(2026, 5, 20)
        val range = DayRange(start, end)
        assertEquals(DayRole.START, MonthGrid.roleOf(start, range))
        assertEquals(DayRole.END, MonthGrid.roleOf(end, range))
        assertEquals(DayRole.MIDDLE, MonthGrid.roleOf(LocalDate.of(2026, 5, 15), range))
        assertEquals(DayRole.NONE, MonthGrid.roleOf(start.minusDays(1), range))
        assertEquals(DayRole.NONE, MonthGrid.roleOf(end.plusDays(1), range))
    }

    @Test
    fun `a one-day range is a single endpoint, not a start and an end`() {
        val day = LocalDate.of(2026, 5, 10)
        assertEquals(DayRole.SINGLE, MonthGrid.roleOf(day, DayRange(day, day)))
    }
}
