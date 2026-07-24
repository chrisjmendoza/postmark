package com.plusorminustwo.postmark.domain.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderTimesTest {

    private val ny = ZoneId.of("America/New_York")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = ny): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun presets(nowMs: Long, zone: ZoneId = ny) = reminderPresets(nowMs, zone)
    private fun label(nowMs: Long, i: Int, zone: ZoneId = ny) = presets(nowMs, zone)[i].label
    private fun millis(nowMs: Long, i: Int, zone: ZoneId = ny) = presets(nowMs, zone)[i].epochMillis

    @Test
    fun `returns three presets in fixed order`() {
        val p = presets(at(2026, 7, 24, 10, 0))
        assertEquals(3, p.size)
        assertEquals("In 1 hour", p[0].label)
        assertEquals("Tomorrow morning (9 AM)", p[2].label)
    }

    @Test
    fun `in one hour is exactly one hour after now`() {
        val now = at(2026, 7, 24, 10, 15)
        assertEquals(now + 3_600_000L, millis(now, 0))
    }

    @Test
    fun `before 5 PM offers this evening at 6 PM today`() {
        val now = at(2026, 7, 24, 14, 0)  // 2 PM
        assertEquals("This evening (6 PM)", label(now, 1))
        assertEquals(at(2026, 7, 24, 18, 0), millis(now, 1))
    }

    @Test
    fun `exactly 5 PM is past the cutoff and rolls evening to tomorrow`() {
        val now = at(2026, 7, 24, 17, 0)  // 5 PM sharp — cutoff is inclusive of "past"
        assertEquals("Tomorrow evening (6 PM)", label(now, 1))
        assertEquals(at(2026, 7, 25, 18, 0), millis(now, 1))
    }

    @Test
    fun `after 5 PM offers tomorrow evening at 6 PM`() {
        val now = at(2026, 7, 24, 20, 30)  // 8:30 PM
        assertEquals("Tomorrow evening (6 PM)", label(now, 1))
        assertEquals(at(2026, 7, 25, 18, 0), millis(now, 1))
    }

    @Test
    fun `just before 5 PM still offers this evening`() {
        val now = at(2026, 7, 24, 16, 59)
        assertEquals("This evening (6 PM)", label(now, 1))
        assertEquals(at(2026, 7, 24, 18, 0), millis(now, 1))
    }

    @Test
    fun `tomorrow morning is 9 AM the next calendar day`() {
        val now = at(2026, 7, 24, 23, 30)  // late night
        assertEquals(at(2026, 7, 25, 9, 0), millis(now, 2))
    }

    @Test
    fun `every preset is strictly in the future`() {
        // Sample across the day including the boundary hours.
        for (hour in listOf(0, 8, 16, 17, 18, 23)) {
            val now = at(2026, 7, 24, hour, 0)
            presets(now).forEach { p ->
                assertTrue("${p.label} should be after now (hour=$hour)", p.epochMillis > now)
            }
        }
    }

    @Test
    fun `tomorrow morning is DST-safe across a spring-forward day`() {
        // US spring-forward 2026: clocks jump 2 AM -> 3 AM on Sun Mar 8. Reminding the night
        // before must still land at 9 AM local, not a naive now+24h (which would be 10 AM).
        val nightBefore = at(2026, 3, 7, 22, 0)
        assertEquals(at(2026, 3, 8, 9, 0), millis(nightBefore, 2))
    }
}
