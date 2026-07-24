package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.dao.MessageMeta
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * Tests for [buildThreadStatsData] and [buildGlobalStatsData].
 * Pure JVM — no Android runtime, no org.json, no Room.
 *
 * The build functions now take the SQL aggregate + lean [MessageMeta] projection + the
 * message bodies; these helpers convert a [MessageEntity] fixture into that shape so the
 * assertions below are unchanged. A fixed UTC zone keeps day/month buckets deterministic
 * regardless of the CI machine's timezone.
 */
class StatsAlgorithmsTest {

    private val utc = ZoneId.of("UTC")

    private fun List<MessageEntity>.metas() = map { MessageMeta(it.threadId, it.timestamp, it.isSent) }
    private fun List<MessageEntity>.bodies() = filter { it.body.isNotEmpty() }.map { it.body }

    private fun threadStats(msgs: List<MessageEntity>, reactions: List<String> = emptyList()): ThreadStatsData {
        val metas = msgs.metas()
        return buildThreadStatsData(threadAggregateOf(metas), metas, msgs.bodies(), reactions, utc)
    }

    private fun globalStats(msgs: List<MessageEntity>, threadCount: Int, reactions: List<String> = emptyList()): GlobalStatsData {
        val metas = msgs.metas()
        return buildGlobalStatsData(globalAggregateOf(metas), threadCount, metas, msgs.bodies(), reactions, utc)
    }

    // ── buildThreadStatsData ───────────────────────────────────────────────

    @Test
    fun `empty message list returns default ThreadStatsData`() {
        val result = threadStats(emptyList())
        assertEquals(0, result.totalMessages)
        assertEquals(0, result.sentCount)
        assertEquals(0, result.receivedCount)
        assertEquals(0, result.activeDayCount)
        assertEquals(0, result.longestStreakDays)
        assertEquals(0L, result.avgResponseTimeMs)
        assertTrue(result.topEmojis.isEmpty())
    }

    @Test
    fun `single sent message gives correct counts`() {
        val result = threadStats(listOf(msg(1, isSent = true, t = 1_000L)))
        assertEquals(1, result.totalMessages)
        assertEquals(1, result.sentCount)
        assertEquals(0, result.receivedCount)
        assertEquals(1, result.activeDayCount)
        assertEquals(1, result.longestStreakDays)
        assertEquals(1_000L, result.firstMessageAt)
        assertEquals(1_000L, result.lastMessageAt)
    }

    @Test
    fun `mixed sent and received messages counted correctly`() {
        val msgs = listOf(
            msg(1, isSent = true, t = 0L),
            msg(2, isSent = false, t = 1_000L),
            msg(3, isSent = true, t = 2_000L),
            msg(4, isSent = false, t = 3_000L)
        )
        val result = threadStats(msgs)
        assertEquals(4, result.totalMessages)
        assertEquals(2, result.sentCount)
        assertEquals(2, result.receivedCount)
    }

    @Test
    fun `first and last message timestamps set correctly`() {
        val msgs = listOf(
            msg(1, isSent = true, t = 100L),
            msg(2, isSent = false, t = 200L),
            msg(3, isSent = true, t = 50L)   // out-of-order input
        )
        val result = threadStats(msgs)
        assertEquals(50L, result.firstMessageAt)
        assertEquals(200L, result.lastMessageAt)
    }

    @Test
    fun `active day count distinct days`() {
        // 3 messages on 2 different days
        val day1 = 0L                           // epoch start (1970-01-01)
        val day2 = 86_400_000L                  // +1 day
        val msgs = listOf(
            msg(1, isSent = true, t = day1),
            msg(2, isSent = false, t = day1 + 3600_000),
            msg(3, isSent = true, t = day2)
        )
        val result = threadStats(msgs)
        assertEquals(2, result.activeDayCount)
    }

    @Test
    fun `top emojis extracted and counted`() {
        val msgs = listOf(
            msg(1, isSent = true, t = 0L, body = "😂😂🎉"),
            msg(2, isSent = false, t = 1_000L, body = "😂 wow")
        )
        val result = threadStats(msgs)
        assertEquals(3, result.topEmojis["😂"])
        assertEquals(1, result.topEmojis["🎉"])
    }

    @Test
    fun `top emojis capped at six entries`() {
        // 7 different emojis in one message
        val body = "😀😁😂🤣😃😄😅"  // 7 emoji
        val msgs = listOf(msg(1, isSent = true, t = 0L, body = body))
        val result = threadStats(msgs)
        assertTrue(result.topEmojis.size <= 6)
    }

    @Test
    fun `byDayOfWeek has correct sum`() {
        val msgs = (1..7).map { i ->
            // Use known timestamps: Jan 6 2025 (Mon) + i-1 days
            val monday = 1736121600_000L  // 2025-01-06 00:00 UTC
            msg(i.toLong(), isSent = true, t = monday + (i - 1) * 86_400_000L)
        }
        val result = threadStats(msgs)
        assertEquals(7, result.byDayOfWeek.sum())
    }

    @Test
    fun `byMonth has correct sum`() {
        val msgs = listOf(
            msg(1, isSent = true, t = 0L),           // Jan 1970
            msg(2, isSent = false, t = 86_400_000L)  // Jan 1970 (same month)
        )
        val result = threadStats(msgs)
        assertEquals(2, result.byMonth.sum())
    }

    @Test
    fun `avg response time computed correctly for direction changes`() {
        val msgs = listOf(
            msg(1, isSent = true, t = 0L),
            msg(2, isSent = false, t = 60_000L)   // 1-min response
        )
        val result = threadStats(msgs)
        assertEquals(60_000L, result.avgResponseTimeMs)
    }

    // ── buildGlobalStatsData ───────────────────────────────────────────────

    @Test
    fun `empty messages returns global default with correct threadCount`() {
        val result = globalStats(emptyList(), threadCount = 5)
        assertEquals(0, result.totalMessages)
        assertEquals(5, result.threadCount)
    }

    @Test
    fun `global stats aggregates across multiple threads`() {
        val msgs = listOf(
            msg(1, isSent = true, t = 0L, threadId = 1L),
            msg(2, isSent = false, t = 60_000L, threadId = 1L),
            msg(3, isSent = true, t = 0L, threadId = 2L),
            msg(4, isSent = false, t = 30_000L, threadId = 2L)
        )
        val result = globalStats(msgs, threadCount = 2)
        assertEquals(4, result.totalMessages)
        assertEquals(2, result.sentCount)
        assertEquals(2, result.receivedCount)
        assertEquals(2, result.threadCount)
    }

    @Test
    fun `global avg response is weighted by thread message count`() {
        // Thread 1: 2 messages, avg response = 60_000 ms
        // Thread 2: 2 messages, avg response = 30_000 ms
        // Weighted avg = (60_000 * 2 + 30_000 * 2) / 4 = 45_000
        val msgs = listOf(
            msg(1, isSent = true, t = 0L, threadId = 1L),
            msg(2, isSent = false, t = 60_000L, threadId = 1L),
            msg(3, isSent = true, t = 0L, threadId = 2L),
            msg(4, isSent = false, t = 30_000L, threadId = 2L)
        )
        val result = globalStats(msgs, threadCount = 2)
        assertEquals(45_000L, result.avgResponseTimeMs)
    }

    // ── heatmapTierForCount ───────────────────────────────────────────────

    @Test
    fun `tier 0 for zero count`() = assertEquals(0, heatmapTierForCount(0, 100))

    @Test
    fun `tier 0 for negative count`() = assertEquals(0, heatmapTierForCount(-5, 100))

    @Test
    fun `tier 0 when max count is zero`() = assertEquals(0, heatmapTierForCount(0, 0))

    @Test
    fun `tier 6 for count equal to max`() {
        assertEquals(6, heatmapTierForCount(100, 100))
        assertEquals(6, heatmapTierForCount(1, 1))
    }

    @Test
    fun `tier scales proportionally to max count`() {
        // max = 100: tiers land on multiples of ~16.7
        assertEquals(1, heatmapTierForCount(1, 100))
        assertEquals(1, heatmapTierForCount(16, 100))
        assertEquals(2, heatmapTierForCount(17, 100))
        assertEquals(3, heatmapTierForCount(50, 100))
        assertEquals(6, heatmapTierForCount(99, 100))
    }

    @Test
    fun `smallest nonzero count always gets at least tier 1`() {
        assertEquals(1, heatmapTierForCount(1, 1000))
    }

    // ── groupMessagesByDay ────────────────────────────────────────────────

    private fun groupByDay(msgs: List<MessageEntity>) = groupMessagesByDay(msgs.metas(), utc)

    @Test
    fun `groupMessagesByDay empty list returns empty map`() {
        assertTrue(groupByDay(emptyList()).isEmpty())
    }

    @Test
    fun `groupMessagesByDay single message counted for its day`() {
        // 2025-04-26 00:00:00 UTC in millis
        val ts = 1745625600_000L
        val result = groupByDay(listOf(msg(1, isSent = true, t = ts)))
        assertEquals(1, result.values.sum())
        assertEquals(1, result.values.single())
    }

    @Test
    fun `groupMessagesByDay multiple messages same day aggregated`() {
        val dayStart = 1745625600_000L  // 2025-04-26 00:00 UTC
        val msgs = listOf(
            msg(1, isSent = true,  t = dayStart),
            msg(2, isSent = false, t = dayStart + 3_600_000L),
            msg(3, isSent = true,  t = dayStart + 7_200_000L)
        )
        val result = groupByDay(msgs)
        assertEquals(3, result.values.sum())
        assertEquals(1, result.keys.size)
    }

    @Test
    fun `groupMessagesByDay messages on different days produce separate keys`() {
        val day1 = 1745625600_000L  // 2025-04-26
        val day2 = day1 + 86_400_000L  // 2025-04-27
        val day3 = day2 + 86_400_000L  // 2025-04-28
        val msgs = listOf(
            msg(1, isSent = true,  t = day1),
            msg(2, isSent = false, t = day2),
            msg(3, isSent = true,  t = day3)
        )
        val result = groupByDay(msgs)
        assertEquals(3, result.keys.size)
        result.values.forEach { assertEquals(1, it) }
    }

    @Test
    fun `groupMessagesByDay multiple messages across two days counted per day`() {
        val day1 = 1745625600_000L
        val day2 = day1 + 86_400_000L
        val msgs = listOf(
            msg(1, isSent = true,  t = day1),
            msg(2, isSent = false, t = day1 + 1_000L),
            msg(3, isSent = true,  t = day2),
            msg(4, isSent = false, t = day2 + 1_000L),
            msg(5, isSent = true,  t = day2 + 2_000L)
        )
        val result = groupByDay(msgs)
        assertEquals(2, result.keys.size)
        assertEquals(5, result.values.sum())
    }

    // ── countReactionEmojis ────────────────────────────────────────────────

    @Test
    fun `empty list returns empty map`() {
        assertTrue(countReactionEmojis(emptyList()).isEmpty())
    }

    @Test
    fun `single emoji returns count of 1`() {
        val result = countReactionEmojis(listOf("❤️"))
        assertEquals(1, result["❤️"])
    }

    @Test
    fun `duplicate emoji counted correctly`() {
        val result = countReactionEmojis(listOf("❤️", "❤️", "❤️", "👍"))
        assertEquals(3, result["❤️"])
        assertEquals(1, result["👍"])
    }

    @Test
    fun `sorted descending by count`() {
        val result = countReactionEmojis(listOf("👍", "❤️", "❤️", "❤️"))
        val keys = result.keys.toList()
        assertEquals("❤️", keys[0])
        assertEquals("👍", keys[1])
    }

    @Test
    fun `capped at 6 by default`() {
        val reactions = listOf("❤️", "👍", "😂", "😮", "😢", "👎", "🔥", "🎉")  // 8 unique
        assertTrue(countReactionEmojis(reactions).size <= 6)
    }

    @Test
    fun `custom limit respected`() {
        val reactions = listOf("❤️", "👍", "😂", "😮", "😢", "👎")
        assertEquals(3, countReactionEmojis(reactions, limit = 3).size)
    }

    @Test
    fun `reactions tracked separately from message emoji in buildThreadStatsData`() {
        val msgs = listOf(msg(1, isSent = true, t = 0L, body = "😂😂 message"))
        val reactions = listOf("👍", "👍", "🎉")
        val result = threadStats(msgs, reactions)
        // message emoji from body
        assertEquals(2, result.topEmojis["😂"])
        assertNull(result.topEmojis["👍"])
        // reaction emoji from reactions list
        assertEquals(2, result.topReactionEmojis["👍"])
        assertEquals(1, result.topReactionEmojis["🎉"])
        assertNull(result.topReactionEmojis["😂"])
    }

    @Test
    fun `reaction emojis empty when no reactions provided`() {
        val msgs = listOf(msg(1, isSent = true, t = 0L, body = "hi"))
        val result = threadStats(msgs)
        assertTrue(result.topReactionEmojis.isEmpty())
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun msg(
        id: Long,
        isSent: Boolean,
        t: Long,
        threadId: Long = 1L,
        body: String = "hi"
    ) = MessageEntity(
        id = id, threadId = threadId, address = "+1", body = body,
        timestamp = t, isSent = isSent, type = 1
    )
}
