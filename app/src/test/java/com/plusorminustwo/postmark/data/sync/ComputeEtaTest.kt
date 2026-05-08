package com.plusorminustwo.postmark.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SmsHistoryImportWorker.Companion.computeEta].
 *
 * The function takes elapsed time and row counts (no clock access) so every
 * case is fully deterministic.
 */
class ComputeEtaTest {

    private fun eta(elapsedMs: Long, done: Int, total: Int) =
        SmsHistoryImportWorker.computeEta(elapsedMs, done, total)

    // ── Guard cases: return "" ─────────────────────────────────────────────────

    @Test
    fun `done zero returns empty`() {
        assertEquals("", eta(elapsedMs = 5_000, done = 0, total = 100))
    }

    @Test
    fun `done negative returns empty`() {
        assertEquals("", eta(elapsedMs = 5_000, done = -1, total = 100))
    }

    @Test
    fun `done equals total returns empty`() {
        assertEquals("", eta(elapsedMs = 10_000, done = 100, total = 100))
    }

    @Test
    fun `done exceeds total returns empty`() {
        assertEquals("", eta(elapsedMs = 10_000, done = 150, total = 100))
    }

    @Test
    fun `elapsed zero returns empty string or zero seconds`() {
        // 0 elapsed → 0 remaining → "~0s" (not a crash)
        val result = eta(elapsedMs = 0, done = 50, total = 100)
        // remainingMs = 0 → secs = 0 → mins = 0 → "~0s"
        assertEquals("~0s", result)
    }

    // ── Seconds-only format ────────────────────────────────────────────────────

    @Test
    fun `less than one minute returns seconds-only format`() {
        // 50 done of 100 in 45 s → 45 s remaining
        assertEquals("~45s", eta(elapsedMs = 45_000, done = 50, total = 100))
    }

    @Test
    fun `exactly 59 seconds remaining uses seconds format`() {
        // 1 done of 60 in 1 s → 59 s remaining
        assertEquals("~59s", eta(elapsedMs = 1_000, done = 1, total = 60))
    }

    @Test
    fun `exactly 1 second remaining`() {
        assertEquals("~1s", eta(elapsedMs = 99_000, done = 99, total = 100))
    }

    // ── Minutes + seconds format ───────────────────────────────────────────────

    @Test
    fun `exactly 60 seconds remaining formats as 1m 0s`() {
        // 1 done of 61 in 1 s → 60 s remaining
        assertEquals("~1m 0s", eta(elapsedMs = 1_000, done = 1, total = 61))
    }

    @Test
    fun `90 seconds remaining formats as 1m 30s`() {
        // 1 done of 3 in 45 s → 90 s remaining
        assertEquals("~1m 30s", eta(elapsedMs = 45_000, done = 1, total = 3))
    }

    @Test
    fun `large ETA many hours`() {
        // 500 done of 100_000 in 10 s → ~(99500/500)*10 = 1990 s = 33m 10s
        assertEquals("~33m 10s", eta(elapsedMs = 10_000, done = 500, total = 100_000))
    }

    @Test
    fun `realistic mms import midway`() {
        // 5000 done of 108592 in 120s → remaining ≈ 2486.2s = 41m 26s
        val result = eta(elapsedMs = 120_000, done = 5_000, total = 108_592)
        assertTrue("Expected minutes format, got: $result", result.startsWith("~") && result.contains("m"))
    }

    // ── Format structure ──────────────────────────────────────────────────────

    @Test
    fun `result always starts with tilde`() {
        val result = eta(elapsedMs = 10_000, done = 50, total = 100)
        assertTrue("Expected ~ prefix", result.startsWith("~"))
    }

    @Test
    fun `minutes format contains m and s`() {
        val result = eta(elapsedMs = 60_000, done = 1, total = 100)
        assertTrue("Expected 'm' in result: $result", result.contains("m"))
        assertTrue("Expected 's' in result: $result", result.contains("s"))
    }

    @Test
    fun `seconds format ends with s and has no m`() {
        val result = eta(elapsedMs = 10_000, done = 50, total = 55)
        assertTrue("Expected 's' suffix: $result", result.endsWith("s"))
        assertTrue("Should not contain 'm': $result", !result.contains("m"))
    }
}
