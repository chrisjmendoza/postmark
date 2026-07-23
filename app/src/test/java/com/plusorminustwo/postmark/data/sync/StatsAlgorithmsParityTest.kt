package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.dao.MessageMeta
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone

/**
 * Proves two things the "Heatmap query performance" TODO demanded:
 *
 *  1. **Parity** — the new lean-meta + SQL-aggregate build path produces the identical
 *     numbers to the ORIGINAL full-entity path (ported verbatim below as the oracle, with
 *     its SimpleDateFormat + Calendar device-zone bucketing) for randomly generated message
 *     lists, across several fixed zones.
 *  2. **Explicit-zone / DST correctness** — with fixed zones around the 2026 US
 *     spring-forward and fall-back, at UTC+14, and at UTC, near-midnight and
 *     cross-transition messages land on the correct LOCAL day, and streaks count calendar
 *     days (not elapsed hours).
 *
 * Pure JVM — no Android runtime, no Room.
 */
class StatsAlgorithmsParityTest {

    private val zones = listOf(
        "UTC",
        "America/New_York",   // DST transitions
        "Pacific/Kiritimati", // UTC+14, no DST
        "Asia/Kolkata"        // UTC+5:30 half-hour offset
    )

    // ── Oracle: verbatim port of the pre-refactor full-entity logic ───────────────

    private data class OracleStats(
        val totalMessages: Int,
        val sentCount: Int,
        val receivedCount: Int,
        val firstMessageAt: Long,
        val lastMessageAt: Long,
        val activeDayCount: Int,
        val longestStreakDays: Int,
        val avgResponseTimeMs: Long,
        val byDayOfWeek: IntArray,
        val byMonth: IntArray
    )

    private fun oracleThreadStats(messages: List<MessageEntity>, tz: TimeZone): OracleStats {
        if (messages.isEmpty()) return OracleStats(0, 0, 0, 0L, 0L, 0, 0, 0L, IntArray(7), IntArray(12))
        val sorted = messages.sortedBy { it.timestamp }
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = tz }
        val activeDays = sorted.map { dayFmt.format(Date(it.timestamp)) }.distinct().sorted()
        val dow = IntArray(7)
        val month = IntArray(12)
        val cal = Calendar.getInstance(tz)
        sorted.forEach { m ->
            cal.timeInMillis = m.timestamp
            dow[(cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7]++
            month[cal.get(Calendar.MONTH)]++
        }
        // Old avg-response loop over entities (identical arithmetic to computeAvgResponseTimeMs).
        var total = 0L
        var count = 0
        for (i in 1 until sorted.size) {
            val gap = sorted[i].timestamp - sorted[i - 1].timestamp
            if (sorted[i - 1].isSent != sorted[i].isSent && gap in 1..MAX_RESPONSE_GAP_MS) {
                total += gap; count++
            }
        }
        return OracleStats(
            totalMessages = sorted.size,
            sentCount = sorted.count { it.isSent },
            receivedCount = sorted.count { !it.isSent },
            firstMessageAt = sorted.first().timestamp,
            lastMessageAt = sorted.last().timestamp,
            activeDayCount = activeDays.size,
            longestStreakDays = computeLongestStreak(activeDays),
            avgResponseTimeMs = if (count > 0) total / count else 0L,
            byDayOfWeek = dow,
            byMonth = month
        )
    }

    // ── Parity ────────────────────────────────────────────────────────────────

    @Test
    fun `new thread path matches the old full-entity oracle across zones`() {
        val messages = generateMessages(seed = 42, count = 400, threadId = 1L)
        for (z in zones) {
            val tz = TimeZone.getTimeZone(z)
            val zone = ZoneId.of(z)
            val oracle = oracleThreadStats(messages, tz)

            val metas = messages.map { MessageMeta(it.threadId, it.timestamp, it.isSent) }
            val bodies = messages.filter { it.body.isNotEmpty() }.map { it.body }
            val actual = buildThreadStatsData(threadAggregateOf(metas), metas, bodies, emptyList(), zone)

            assertEquals("total ($z)", oracle.totalMessages, actual.totalMessages)
            assertEquals("sent ($z)", oracle.sentCount, actual.sentCount)
            assertEquals("received ($z)", oracle.receivedCount, actual.receivedCount)
            assertEquals("first ($z)", oracle.firstMessageAt, actual.firstMessageAt)
            assertEquals("last ($z)", oracle.lastMessageAt, actual.lastMessageAt)
            assertEquals("activeDayCount ($z)", oracle.activeDayCount, actual.activeDayCount)
            assertEquals("longestStreak ($z)", oracle.longestStreakDays, actual.longestStreakDays)
            assertEquals("avgResponse ($z)", oracle.avgResponseTimeMs, actual.avgResponseTimeMs)
            assertArrayEquals("byDayOfWeek ($z)", oracle.byDayOfWeek, actual.byDayOfWeek)
            assertArrayEquals("byMonth ($z)", oracle.byMonth, actual.byMonth)
        }
    }

    @Test
    fun `new global path matches summed per-thread oracle counts across zones`() {
        // Three threads, generated independently, concatenated.
        val messages =
            generateMessages(seed = 1, count = 150, threadId = 1L) +
            generateMessages(seed = 2, count = 150, threadId = 2L) +
            generateMessages(seed = 3, count = 150, threadId = 3L)
        for (z in zones) {
            val tz = TimeZone.getTimeZone(z)
            val zone = ZoneId.of(z)
            val wholeOracle = oracleThreadStats(messages, tz) // whole-corpus buckets/counts

            val metas = messages.map { MessageMeta(it.threadId, it.timestamp, it.isSent) }
            val bodies = messages.filter { it.body.isNotEmpty() }.map { it.body }
            val global = buildGlobalStatsData(globalAggregateOf(metas), threadCount = 3, metas = metas, emojiBodies = bodies, reactions = emptyList(), zone = zone)

            assertEquals("total ($z)", wholeOracle.totalMessages, global.totalMessages)
            assertEquals("sent ($z)", wholeOracle.sentCount, global.sentCount)
            assertEquals("received ($z)", wholeOracle.receivedCount, global.receivedCount)
            assertEquals("activeDayCount ($z)", wholeOracle.activeDayCount, global.activeDayCount)
            assertEquals("longestStreak ($z)", wholeOracle.longestStreakDays, global.longestStreakDays)
            assertArrayEquals("byDayOfWeek ($z)", wholeOracle.byDayOfWeek, global.byDayOfWeek)
            assertArrayEquals("byMonth ($z)", wholeOracle.byMonth, global.byMonth)
            assertEquals("threadCount", 3, global.threadCount)
        }
    }

    @Test
    fun `SQL-mirror aggregate equals a hand count`() {
        val metas = listOf(
            MessageMeta(7L, 500L, true),
            MessageMeta(7L, 100L, false),
            MessageMeta(7L, 900L, true)
        )
        val agg = threadAggregateOf(metas)
        assertEquals(7L, agg.threadId)
        assertEquals(3, agg.totalMessages)
        assertEquals(2, agg.sentCount)
        assertEquals(100L, agg.firstMessageAt)
        assertEquals(900L, agg.lastMessageAt)
    }

    // ── Explicit-zone / DST correctness ──────────────────────────────────────────

    @Test
    fun `Kiritimati UTC+14 rolls a late-UTC instant to the next local day`() {
        val zone = ZoneId.of("Pacific/Kiritimati")
        // 2026-01-01T10:00Z is 2026-01-02T00:00 (+14) local.
        val ts = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2026, 1, 2), localDay(ts, zone))
    }

    @Test
    fun `New York evening instant buckets to the previous UTC day`() {
        val zone = ZoneId.of("America/New_York")
        // 2026-03-08T02:00Z = 2026-03-07T21:00 EST (UTC-5). UTC day is the 8th; local is the 7th.
        val ts = Instant.parse("2026-03-08T02:00:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2026, 3, 7), localDay(ts, zone))
        assertEquals("differs from the UTC bucket", LocalDate.of(2026, 3, 8), localDay(ts, ZoneId.of("UTC")))
    }

    @Test
    fun `spring-forward day still counts as one active day and a continuous streak`() {
        val zone = ZoneId.of("America/New_York")
        // Messages straddling the 2026-03-08 02:00 spring-forward, plus the day before and after.
        val msgs = listOf(
            metaAtLocal(zone, 2026, 3, 7, 12, 0),
            metaAtLocal(zone, 2026, 3, 8, 1, 30),   // before the gap (EST)
            metaAtLocal(zone, 2026, 3, 8, 3, 30),   // after the gap (EDT) — same local day
            metaAtLocal(zone, 2026, 3, 9, 12, 0)
        )
        val stats = buildThreadStatsData(threadAggregateOf(msgs), msgs, zone = zone)
        assertEquals("three distinct local days", 3, stats.activeDayCount)
        assertEquals("streak spans all three calendar days", 3, stats.longestStreakDays)
    }

    @Test
    fun `fall-back repeated hour keeps both instants on the same local day`() {
        val zone = ZoneId.of("America/New_York")
        // 2026-11-01 the 01:00 hour repeats. Both instants are Nov 1 local.
        val before = Instant.parse("2026-11-01T05:30:00Z").toEpochMilli() // 01:30 EDT
        val after = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()  // 01:30 EST
        assertEquals(LocalDate.of(2026, 11, 1), localDay(before, zone))
        assertEquals(LocalDate.of(2026, 11, 1), localDay(after, zone))
    }

    @Test
    fun `near-midnight messages split across two local days by day-of-week`() {
        val zone = ZoneId.of("America/New_York")
        // 2026-03-09 is a Monday (index 0); 2026-03-08 is a Sunday (index 6).
        val msgs = listOf(
            metaAtLocal(zone, 2026, 3, 8, 23, 59), // Sunday
            metaAtLocal(zone, 2026, 3, 9, 0, 1)    // Monday
        )
        val stats = buildThreadStatsData(threadAggregateOf(msgs), msgs, zone = zone)
        assertEquals("Monday", 1, stats.byDayOfWeek[0])
        assertEquals("Sunday", 1, stats.byDayOfWeek[6])
        assertEquals(2, stats.activeDayCount)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Deterministic timestamp for a local wall-clock time in [zone]. */
    private fun metaAtLocal(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, min: Int): MessageMeta {
        val ts = LocalDate.of(y, mo, d).atTime(h, min).atZone(zone).toInstant().toEpochMilli()
        // Alternate direction so response-time pairs exist where used.
        return MessageMeta(threadId = 1L, timestamp = ts, isSent = (h + min) % 2 == 0)
    }

    /** Generates [count] messages over ~14 months from 2026-01-01, spread across the day
     *  (including near-midnight), with mixed direction and occasional emoji bodies. */
    private fun generateMessages(seed: Long, count: Int, threadId: Long): List<MessageEntity> {
        val rnd = Random(seed)
        val startMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val span = 420L * 24 * 3600_000L // ~14 months
        val emojis = listOf("😂", "🎉", "❤", "👍", "🔥")
        return (0 until count).map { i ->
            val ts = startMs + (rnd.nextDouble() * span).toLong()
            val body = if (rnd.nextInt(4) == 0) "msg ${emojis[rnd.nextInt(emojis.size)]}" else "plain body"
            MessageEntity(
                id = i.toLong() + threadId * 100_000,
                threadId = threadId,
                address = "+1",
                body = body,
                timestamp = ts,
                isSent = rnd.nextBoolean(),
                type = 1
            )
        }
    }
}
