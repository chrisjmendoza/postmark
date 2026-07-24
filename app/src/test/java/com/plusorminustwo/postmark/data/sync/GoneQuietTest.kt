package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.dao.MessageMeta
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Tests for [detectGoneQuiet]. Pure JVM — no Android runtime, no Room.
 *
 * [nowMs] is always fixed and injected so the tests are deterministic regardless of when
 * they run; a fixed UTC zone keeps day bucketing deterministic regardless of the CI
 * machine's timezone.
 */
class GoneQuietTest {

    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-07-23T12:00:00Z").toEpochMilli()
    private val dayMs = 24 * 3600_000L

    /** [count] messages spaced [gapDays] apart, ending [endDaysAgo] before [now]. */
    private fun regularMetas(threadId: Long, count: Int, gapDays: Long, endDaysAgo: Long): List<MessageMeta> {
        val lastTs = now - endDaysAgo * dayMs
        return (0 until count).map { i ->
            MessageMeta(threadId, lastTs - (count - 1 - i) * gapDays * dayMs, isSent = i % 2 == 0)
        }
    }

    // ── Qualifying case ───────────────────────────────────────────────────────

    @Test
    fun `thread with steady daily history that then goes silent for 2 weeks qualifies`() {
        // 30 messages, one per day, ending 14 days ago (usual gap 1 day; quiet 14 >= 4x1).
        val metas = regularMetas(threadId = 1L, count = 30, gapDays = 1, endDaysAgo = 14)
        val result = detectGoneQuiet(mapOf(1L to metas), now, utc)
        assertEquals(1, result.size)
        assertEquals(1L, result[0].threadId)
        assertEquals(14, result[0].daysQuiet)
        assertEquals(1, result[0].usualGapDays)
    }

    // ── Too little history ─────────────────────────────────────────────────────

    @Test
    fun `thread with fewer than 20 messages in the window never qualifies`() {
        // Only 10 messages, even though it's been quiet a long time.
        val metas = regularMetas(threadId = 1L, count = 10, gapDays = 1, endDaysAgo = 30)
        val result = detectGoneQuiet(mapOf(1L to metas), now, utc)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `brand new thread with no history is not flagged`() {
        val result = detectGoneQuiet(mapOf(1L to emptyList()), now, utc)
        assertTrue(result.isEmpty())
    }

    // ── Quiet but within usual cadence (must NOT flag) ──────────────────────────

    @Test
    fun `sporadic thread that is quiet but within its usual gap is not flagged`() {
        // Usual gap is ~3 days (messages every 3 days); only quiet 10 days — under 4x3=12.
        val metas = regularMetas(threadId = 1L, count = 25, gapDays = 3, endDaysAgo = 10)
        val result = detectGoneQuiet(mapOf(1L to metas), now, utc)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `retired sporadic thread far past its usual gap does qualify`() {
        // Same ~3 day usual cadence, but now quiet for 20 days — over 4x3=12.
        val metas = regularMetas(threadId = 1L, count = 25, gapDays = 3, endDaysAgo = 20)
        val result = detectGoneQuiet(mapOf(1L to metas), now, utc)
        assertEquals(1, result.size)
        assertEquals(20, result[0].daysQuiet)
        assertEquals(3, result[0].usualGapDays)
    }

    // ── Quiet under the flat 7-day floor (must NOT flag even if 4x gap is smaller) ──

    @Test
    fun `quiet for under 7 days never qualifies even with a tiny usual gap`() {
        // Usual gap ~1 day (4x = 4 days, satisfied by a 5-day gap) but the flat 7-day floor
        // is not met.
        val metas = regularMetas(threadId = 1L, count = 25, gapDays = 1, endDaysAgo = 5)
        val result = detectGoneQuiet(mapOf(1L to metas), now, utc)
        assertTrue(result.isEmpty())
    }

    // ── Burst-then-quiet (median must resist a burst inflating/deflating gap) ──────

    @Test
    fun `burst of same-day messages then quiet still qualifies via the degenerate-gap floor`() {
        // 25 messages all sent on the same day 10 days ago — median gap is ~0, floored to 1.
        // 10 days quiet >= 4x1 = 4, and >= the flat 7-day floor.
        val burstDay = now - 10 * dayMs
        val metas = (0 until 25).map { i -> MessageMeta(1L, burstDay + i * 60_000L, isSent = i % 2 == 0) }
        val result = detectGoneQuiet(mapOf(1L to metas), now, utc)
        assertEquals(1, result.size)
        assertEquals(10, result[0].daysQuiet)
        assertEquals(1, result[0].usualGapDays)
    }

    @Test
    fun `daily history with one large burst is not thrown off by the burst (median, not mean)`() {
        // 24 daily messages (usual gap 1 day) plus one extra burst message crammed into the
        // last day of activity. A MEAN would be pulled down by the near-zero burst gap and
        // stay basically the same either way here, so this instead proves the burst doesn't
        // corrupt the qualifying decision: still ~1 day usual gap, 10 days quiet qualifies.
        val dailyMetas = regularMetas(threadId = 1L, count = 24, gapDays = 1, endDaysAgo = 10).toMutableList()
        val lastTs = dailyMetas.maxOf { it.timestamp }
        dailyMetas.add(MessageMeta(1L, lastTs + 60_000L, isSent = true)) // burst reply same day
        val result = detectGoneQuiet(mapOf(1L to dailyMetas), now, utc)
        assertEquals(1, result.size)
        assertEquals(1, result[0].usualGapDays)
    }

    // ── Same-day degenerate gaps guard ───────────────────────────────────────────

    @Test
    fun `usualGapDays is never reported as less than 1 day`() {
        val burstDay = now - 20 * dayMs
        val metas = (0 until 22).map { i -> MessageMeta(1L, burstDay + i * 30_000L, isSent = i % 2 == 0) }
        val result = detectGoneQuiet(mapOf(1L to metas), now, utc)
        assertEquals(1, result.size)
        assertTrue(result[0].usualGapDays >= 1)
    }

    // ── Sorting and cap ───────────────────────────────────────────────────────

    @Test
    fun `results are sorted by daysQuiet descending`() {
        val metas = mapOf(
            1L to regularMetas(1L, 25, gapDays = 1, endDaysAgo = 8),
            2L to regularMetas(2L, 25, gapDays = 1, endDaysAgo = 20),
            3L to regularMetas(3L, 25, gapDays = 1, endDaysAgo = 14)
        )
        val result = detectGoneQuiet(metas, now, utc)
        assertEquals(listOf(2L, 3L, 1L), result.map { it.threadId })
    }

    @Test
    fun `results are capped at the requested limit`() {
        val metas = (1L..5L).associateWith { regularMetas(it, 25, gapDays = 1, endDaysAgo = 10 + it) }
        val result = detectGoneQuiet(metas, now, utc, limit = 3)
        assertEquals(3, result.size)
    }

    @Test
    fun `default limit is 3`() {
        val metas = (1L..5L).associateWith { regularMetas(it, 25, gapDays = 1, endDaysAgo = 10 + it) }
        val result = detectGoneQuiet(metas, now, utc)
        assertEquals(3, result.size)
    }

    // ── Determinism / injected now+zone ─────────────────────────────────────────

    @Test
    fun `same inputs at different injected now values produce different daysQuiet deterministically`() {
        val metas = regularMetas(threadId = 1L, count = 25, gapDays = 1, endDaysAgo = 10)
        val laterNow = now + 5 * dayMs
        val resultNow = detectGoneQuiet(mapOf(1L to metas), now, utc)
        val resultLater = detectGoneQuiet(mapOf(1L to metas), laterNow, utc)
        assertEquals(10, resultNow[0].daysQuiet)
        assertEquals(15, resultLater[0].daysQuiet)
    }

    @Test
    fun `differing zones can shift which local day the last message and now fall on`() {
        // A message at 2026-07-10T02:30Z is 2026-07-10 in UTC but rolls back to 2026-07-09
        // in America/New_York (UTC-4 in July) — one MORE quiet day counted in New York,
        // since the last message's local day is a day earlier there.
        val zoneNY = ZoneId.of("America/New_York")
        val lastTs = Instant.parse("2026-07-10T02:30:00Z").toEpochMilli()
        val history = (0 until 24).map { i ->
            MessageMeta(1L, lastTs - (24 - i) * dayMs, isSent = i % 2 == 0)
        }
        val metas = history + MessageMeta(1L, lastTs, isSent = true)
        val resultUtc = detectGoneQuiet(mapOf(1L to metas), now, utc)
        val resultNY = detectGoneQuiet(mapOf(1L to metas), now, zoneNY)
        assertEquals(1, resultUtc.size)
        assertEquals(1, resultNY.size)
        assertEquals(13, resultUtc[0].daysQuiet)
        assertEquals(14, resultNY[0].daysQuiet)
    }
}
