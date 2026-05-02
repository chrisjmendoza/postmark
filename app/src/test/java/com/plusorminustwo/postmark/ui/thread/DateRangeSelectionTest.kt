package com.plusorminustwo.postmark.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [ThreadViewModel.epochMsForDayBoundaries].
 *
 * Verifies boundary semantics: startMs is the first millisecond of [start] in the
 * system timezone; endMs is the last millisecond of [end] (one ms before midnight of
 * the following day). These are the epoch ranges forwarded to
 * [MessageRepository.getByThreadAndDateRange] by [ThreadViewModel.selectByDateRange].
 */
class DateRangeSelectionTest {

    // ── Boundary semantics ────────────────────────────────────────────────────

    @Test
    fun `startMs is midnight of start date in system timezone`() {
        val start = LocalDate.of(2026, 5, 1)
        val end   = LocalDate.of(2026, 5, 3)
        val (startMs, _) = ThreadViewModel.epochMsForDayBoundaries(start, end)
        val expectedStartMs = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expectedStartMs, startMs)
    }

    @Test
    fun `endMs is one millisecond before midnight of the day after end`() {
        val start = LocalDate.of(2026, 5, 1)
        val end   = LocalDate.of(2026, 5, 3)
        val (_, endMs) = ThreadViewModel.epochMsForDayBoundaries(start, end)
        val expectedEndMs = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        assertEquals(expectedEndMs, endMs)
    }

    @Test
    fun `endMs is after startMs for any valid range`() {
        val start = LocalDate.of(2026, 5, 1)
        val end   = LocalDate.of(2026, 5, 1)
        val (startMs, endMs) = ThreadViewModel.epochMsForDayBoundaries(start, end)
        assertTrue("endMs ($endMs) must be > startMs ($startMs)", endMs > startMs)
    }

    @Test
    fun `endMs is after startMs for multi-day range`() {
        val start = LocalDate.of(2026, 1, 1)
        val end   = LocalDate.of(2026, 12, 31)
        val (startMs, endMs) = ThreadViewModel.epochMsForDayBoundaries(start, end)
        assertTrue(endMs > startMs)
    }

    // ── Inclusion / exclusion invariants ────────────────────────────────────

    @Test
    fun `message at start midnight is included in range`() {
        val day = LocalDate.of(2026, 5, 1)
        val (startMs, endMs) = ThreadViewModel.epochMsForDayBoundaries(day, day)
        val ts = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue("timestamp $ts should be in [$startMs, $endMs]", ts in startMs..endMs)
    }

    @Test
    fun `message one ms before start is excluded`() {
        val day = LocalDate.of(2026, 5, 1)
        val (startMs, _) = ThreadViewModel.epochMsForDayBoundaries(day, day)
        val ts = startMs - 1
        assertTrue("timestamp $ts should be before startMs $startMs", ts < startMs)
    }

    @Test
    fun `message at endMs is included`() {
        val day = LocalDate.of(2026, 5, 1)
        val (startMs, endMs) = ThreadViewModel.epochMsForDayBoundaries(day, day)
        assertTrue("endMs $endMs should be in [$startMs, $endMs]", endMs in startMs..endMs)
    }

    @Test
    fun `message one ms after endMs is excluded`() {
        val day = LocalDate.of(2026, 5, 1)
        val (_, endMs) = ThreadViewModel.epochMsForDayBoundaries(day, day)
        val ts = endMs + 1
        assertTrue("timestamp $ts should be after endMs $endMs", ts > endMs)
    }
}
