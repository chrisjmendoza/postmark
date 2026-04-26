package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageGroupingTest {

    private val MIN = 60_000L
    private val DAY = 24 * 60 * MIN  // 86_400_000 ms

    private fun msg(id: Long, isSent: Boolean, ts: Long) = Message(
        id = id, threadId = 1L, address = "+1", body = "x",
        timestamp = ts, isSent = isSent, type = 1
    )

    // ── empty / single ─────────────────────────────────────────────────────────

    @Test
    fun `empty list returns empty map`() {
        assertEquals(emptyMap<Long, ClusterPosition>(), computeClusterPositions(emptyList()))
    }

    @Test
    fun `single message is SINGLE`() {
        val result = computeClusterPositions(listOf(msg(1, true, 0)))
        assertEquals(ClusterPosition.SINGLE, result[1])
    }

    // ── two-message clusters ────────────────────────────────────────────────────

    @Test
    fun `two same-sender within 3 min produces TOP then BOTTOM`() {
        val msgs = listOf(msg(1, true, 0), msg(2, true, 2 * MIN))
        val result = computeClusterPositions(msgs)
        assertEquals(ClusterPosition.TOP,    result[1])
        assertEquals(ClusterPosition.BOTTOM, result[2])
    }

    @Test
    fun `two same-sender at exactly 3 min still clusters`() {
        val msgs = listOf(msg(1, true, 0), msg(2, true, 3 * MIN))
        val result = computeClusterPositions(msgs)
        assertEquals(ClusterPosition.TOP,    result[1])
        assertEquals(ClusterPosition.BOTTOM, result[2])
    }

    @Test
    fun `two same-sender beyond 3 min are both SINGLE`() {
        val msgs = listOf(msg(1, true, 0), msg(2, true, 3 * MIN + 1))
        val result = computeClusterPositions(msgs)
        assertEquals(ClusterPosition.SINGLE, result[1])
        assertEquals(ClusterPosition.SINGLE, result[2])
    }

    // ── three-message cluster ──────────────────────────────────────────────────

    @Test
    fun `three same-sender within 3 min produces TOP MIDDLE BOTTOM`() {
        val msgs = listOf(
            msg(1, true, 0),
            msg(2, true, 1 * MIN),
            msg(3, true, 2 * MIN)
        )
        val result = computeClusterPositions(msgs)
        assertEquals(ClusterPosition.TOP,    result[1])
        assertEquals(ClusterPosition.MIDDLE, result[2])
        assertEquals(ClusterPosition.BOTTOM, result[3])
    }

    // ── sender boundary ────────────────────────────────────────────────────────

    @Test
    fun `different senders do not cluster even within 3 min`() {
        val msgs = listOf(
            msg(1, isSent = true,  ts = 0),
            msg(2, isSent = false, ts = 1 * MIN)
        )
        val result = computeClusterPositions(msgs)
        assertEquals(ClusterPosition.SINGLE, result[1])
        assertEquals(ClusterPosition.SINGLE, result[2])
    }

    @Test
    fun `cluster ends at sender switch then resumes with new sender`() {
        // sent: 0, 1 min → cluster (TOP, BOTTOM)
        // received: 2 min → SINGLE (different sender)
        val msgs = listOf(
            msg(1, isSent = true,  ts = 0),
            msg(2, isSent = true,  ts = 1 * MIN),
            msg(3, isSent = false, ts = 2 * MIN)
        )
        val result = computeClusterPositions(msgs)
        assertEquals(ClusterPosition.TOP,    result[1])
        assertEquals(ClusterPosition.BOTTOM, result[2])
        assertEquals(ClusterPosition.SINGLE, result[3])
    }

    // ── gap breaks cluster ─────────────────────────────────────────────────────

    @Test
    fun `gap in same-sender run breaks into two clusters`() {
        // msgs 1-2: cluster; gap > 3 min; msgs 3-4: new cluster
        val msgs = listOf(
            msg(1, true, 0),
            msg(2, true, 2 * MIN),
            msg(3, true, 10 * MIN),
            msg(4, true, 11 * MIN)
        )
        val result = computeClusterPositions(msgs)
        assertEquals(ClusterPosition.TOP,    result[1])
        assertEquals(ClusterPosition.BOTTOM, result[2])
        assertEquals(ClusterPosition.TOP,    result[3])
        assertEquals(ClusterPosition.BOTTOM, result[4])
    }

    // ── mixed realistic conversation ───────────────────────────────────────────

    @Test
    fun `realistic alternating conversation has all SINGLE`() {
        val msgs = listOf(
            msg(1, isSent = true,  ts = 0),
            msg(2, isSent = false, ts = 5 * MIN),
            msg(3, isSent = true,  ts = 10 * MIN),
            msg(4, isSent = false, ts = 15 * MIN)
        )
        val result = computeClusterPositions(msgs)
        result.values.forEach { assertEquals(ClusterPosition.SINGLE, it) }
    }

    @Test
    fun `long same-sender burst produces correct boundary positions`() {
        // 5 consecutive sent messages 1 min apart → TOP MIDDLE MIDDLE MIDDLE BOTTOM
        val msgs = (1L..5L).map { msg(it, true, (it - 1) * MIN) }
        val result = computeClusterPositions(msgs)
        assertEquals(ClusterPosition.TOP,    result[1])
        assertEquals(ClusterPosition.MIDDLE, result[2])
        assertEquals(ClusterPosition.MIDDLE, result[3])
        assertEquals(ClusterPosition.MIDDLE, result[4])
        assertEquals(ClusterPosition.BOTTOM, result[5])
    }

    // ── groupByDay ─────────────────────────────────────────────────────────────
    // Epoch 0 = 1970-01-01 00:00:00 UTC. Tests use offsets that are safely within
    // a single calendar day regardless of local timezone (midday ± a few hours).

    private val NOON = 12 * 60 * MIN   // 12:00 on day 0
    private val day0 = NOON            // day 0 noon
    private val day1 = NOON + DAY      // day 1 noon
    private val day2 = NOON + 2 * DAY  // day 2 noon

    @Test
    fun `empty list produces empty groups`() {
        assertTrue(emptyList<Message>().groupByDay().isEmpty())
    }

    @Test
    fun `single message produces one group`() {
        val result = listOf(msg(1, true, day0)).groupByDay()
        assertEquals(1, result.size)
        assertEquals(1, result.values.first().size)
    }

    @Test
    fun `messages on the same day are in one group`() {
        val msgs = listOf(
            msg(1, true,  day0),
            msg(2, false, day0 + 30 * MIN),
            msg(3, true,  day0 + 60 * MIN)
        )
        assertEquals(1, msgs.groupByDay().size)
    }

    @Test
    fun `messages across two days produce two groups`() {
        val msgs = listOf(msg(1, true, day0), msg(2, true, day1))
        assertEquals(2, msgs.groupByDay().size)
    }

    @Test
    fun `messages across three days produce three groups`() {
        val msgs = listOf(msg(1, true, day0), msg(2, true, day1), msg(3, true, day2))
        assertEquals(3, msgs.groupByDay().size)
    }

    @Test
    fun `group keys are in ascending day order — oldest day first`() {
        // The LazyColumn iterates entries().reversed(), so this contract must hold
        // for display order to be correct (newest message at bottom of screen).
        val msgs = listOf(
            msg(1, true, day0),
            msg(2, true, day1),
            msg(3, true, day2)
        )
        val keys = msgs.groupByDay().keys.toList()
        val labels = msgs.groupByDay().keys.toList()
        assertEquals("oldest day must be first key", labels[0], keys[0])
        assertEquals("newest day must be last key",  labels[2], keys[2])
        // Verify the key order actually reflects ascending timestamps
        val timestamps = keys.map { label ->
            msgs.first { DAY_FORMATTER.format(java.util.Date(it.timestamp)) == label }.timestamp
        }
        assertEquals(timestamps, timestamps.sorted())
    }

    @Test
    fun `within each group messages are in ascending timestamp order`() {
        val msgs = listOf(
            msg(1, true,  day0),
            msg(2, false, day0 + 30 * MIN),
            msg(3, true,  day0 + 60 * MIN)
        )
        val group = msgs.groupByDay().values.first()
        assertEquals(listOf(1L, 2L, 3L), group.map { it.id })
    }

    @Test
    fun `reversed entries puts newest day first — matches LazyColumn render order`() {
        val msgs = listOf(
            msg(1, true, day0),
            msg(2, true, day1),
            msg(3, true, day2)
        )
        val reversed = msgs.groupByDay().entries.reversed()
        val newestGroupMessages = reversed.first().value
        assertEquals("newest day should be first after reversal", day2, newestGroupMessages.first().timestamp)
    }

    @Test
    fun `reversed entries last group has oldest day — appears at top of screen`() {
        val msgs = listOf(
            msg(1, true, day0),
            msg(2, true, day1),
            msg(3, true, day2)
        )
        val reversed = msgs.groupByDay().entries.reversed()
        val oldestGroupMessages = reversed.last().value
        assertEquals("oldest day should be last after reversal", day0, oldestGroupMessages.first().timestamp)
    }
}
