package com.plusorminustwo.postmark.ui.stats

import org.junit.Assert.*
import org.junit.Test

class ChartMathTest {

    // ── doughnutSweeps ────────────────────────────────────────────────────────

    @Test
    fun `equal sent and received split the ring evenly`() {
        val (sentSweep, receivedSweep) = doughnutSweeps(sent = 10, received = 10)
        assertEquals(180f, sentSweep, 0.001f)
        assertEquals(180f, receivedSweep, 0.001f)
    }

    @Test
    fun `all sent gives a full 360 degree sent sweep and zero received`() {
        val (sentSweep, receivedSweep) = doughnutSweeps(sent = 5, received = 0)
        assertEquals(360f, sentSweep, 0.001f)
        assertEquals(0f, receivedSweep, 0.001f)
    }

    @Test
    fun `all received gives a full 360 degree received sweep and zero sent`() {
        val (sentSweep, receivedSweep) = doughnutSweeps(sent = 0, received = 7)
        assertEquals(0f, sentSweep, 0.001f)
        assertEquals(360f, receivedSweep, 0.001f)
    }

    @Test
    fun `zero total returns a zero,zero pair instead of dividing by zero`() {
        val (sentSweep, receivedSweep) = doughnutSweeps(sent = 0, received = 0)
        assertEquals(0f, sentSweep, 0.001f)
        assertEquals(0f, receivedSweep, 0.001f)
    }

    @Test
    fun `sweeps always sum to exactly 360 degrees regardless of ratio`() {
        val cases = listOf(1 to 2, 3 to 1, 100 to 1, 1 to 100, 17 to 23)
        cases.forEach { (sent, received) ->
            val (sentSweep, receivedSweep) = doughnutSweeps(sent, received)
            assertEquals("sent=$sent received=$received", 360f, sentSweep + receivedSweep, 0.001f)
        }
    }

    @Test
    fun `uneven split is proportional`() {
        val (sentSweep, receivedSweep) = doughnutSweeps(sent = 1, received = 3)
        assertEquals(90f, sentSweep, 0.001f)
        assertEquals(270f, receivedSweep, 0.001f)
    }

    // ── barFraction ───────────────────────────────────────────────────────────

    @Test
    fun `bar equal to max gets full fraction`() {
        assertEquals(1f, barFraction(count = 5, maxCount = 5), 0.001f)
    }

    @Test
    fun `bar at half of max gets half fraction`() {
        assertEquals(0.5f, barFraction(count = 5, maxCount = 10), 0.001f)
    }

    @Test
    fun `zero count against a positive max gives zero fraction`() {
        assertEquals(0f, barFraction(count = 0, maxCount = 10), 0.001f)
    }

    @Test
    fun `zero max never divides by zero and returns zero`() {
        assertEquals(0f, barFraction(count = 0, maxCount = 0), 0.001f)
        assertEquals(0f, barFraction(count = 5, maxCount = 0), 0.001f)
    }

    @Test
    fun `fraction is coerced within 0 and 1 even if count exceeds max`() {
        // Shouldn't happen in practice (max is derived from the same data as count),
        // but the guard should still hold.
        assertEquals(1f, barFraction(count = 15, maxCount = 10), 0.001f)
    }
}
