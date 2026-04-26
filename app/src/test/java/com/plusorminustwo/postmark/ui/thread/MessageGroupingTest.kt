package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageGroupingTest {

    private val MIN = 60_000L

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
}
