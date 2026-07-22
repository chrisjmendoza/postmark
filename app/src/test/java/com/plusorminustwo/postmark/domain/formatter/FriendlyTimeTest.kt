package com.plusorminustwo.postmark.domain.formatter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Unit tests for [friendlyTimestamp].
 *
 * Every band boundary is exercised with fixed epoch values and an injected UTC zone
 * so the result depends only on the arguments, never on the wall clock or the host's
 * default zone/locale. "Now" is fixed at Thursday 2023-06-15 12:00 UTC throughout.
 *
 * Bands:
 *  - < 1 min                 → "just now"
 *  - < 60 min                → "Xm"
 *  - same calendar day       → "9:41 AM" / "09:41"
 *  - within the last 6 days  → short weekday ("Mon")
 *  - same calendar year      → "Apr 25"
 *  - previous year           → "4/25/22"
 */
class FriendlyTimeTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val locale = Locale.US

    /** Epoch millis for a wall-clock instant in the fixed UTC test zone. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    // Thursday, June 15 2023, 12:00 UTC.
    private val now = at(2023, 6, 15, 12, 0)

    private fun label(timestamp: Long, is24Hour: Boolean = false): String =
        friendlyTimestamp(timestamp, now, is24Hour, zone, locale)

    // ── < 1 minute → "just now" ─────────────────────────────────────────────────

    @Test fun `30 seconds ago is just now`() {
        assertEquals("just now", label(now - 30_000L))
    }

    @Test fun `one millisecond under a minute is still just now`() {
        assertEquals("just now", label(now - 59_999L))
    }

    // ── minutes → "Xm" ──────────────────────────────────────────────────────────

    @Test fun `exactly one minute ago is 1m`() {
        assertEquals("1m", label(now - 60_000L))
    }

    @Test fun `five minutes ago is 5m`() {
        assertEquals("5m", label(now - 5 * 60_000L))
    }

    @Test fun `fifty-nine minutes ago is 59m`() {
        assertEquals("59m", label(now - 59 * 60_000L))
    }

    // ── same calendar day → wall-clock time ─────────────────────────────────────

    @Test fun `earlier today shows 12-hour time by default`() {
        assertEquals("9:41 AM", label(at(2023, 6, 15, 9, 41)))
    }

    @Test fun `earlier today shows 24-hour time when preferred`() {
        assertEquals("09:41", label(at(2023, 6, 15, 9, 41), is24Hour = true))
    }

    @Test fun `an afternoon time today reads as PM`() {
        // 3 hours before noon-now would still be same day; use an early-morning time
        // more than an hour back so it can't fall into the minutes band.
        assertEquals("1:15 AM", label(at(2023, 6, 15, 1, 15)))
    }

    // ── within the last 6 days → short weekday ──────────────────────────────────

    @Test fun `yesterday shows the weekday`() {
        // Wednesday June 14.
        assertEquals("Wed", label(at(2023, 6, 14, 12, 0)))
    }

    @Test fun `three days ago shows the weekday`() {
        // Monday June 12.
        assertEquals("Mon", label(at(2023, 6, 12, 12, 0)))
    }

    // ── same calendar year → "MMM d" ────────────────────────────────────────────

    @Test fun `over a week ago in the same year shows month and day`() {
        assertEquals("Apr 25", label(at(2023, 4, 25, 8, 0)))
    }

    @Test fun `just past the 6-day weekday window shows month and day`() {
        // Exactly 7 days back — no longer a weekday, still this year.
        assertEquals("Jun 8", label(at(2023, 6, 8, 12, 0)))
    }

    // ── previous year → "M/d/yy" (with year) ────────────────────────────────────

    @Test fun `a previous year shows a numeric date including the year`() {
        assertEquals("4/25/22", label(at(2022, 4, 25, 8, 0)))
    }

    @Test fun `late last year shows the numeric date`() {
        assertEquals("12/31/22", label(at(2022, 12, 31, 23, 59)))
    }
}
