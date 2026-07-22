package com.plusorminustwo.postmark.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [pickOptimisticMatch] — the pure decision behind syncLatestMms's
 * optimistic→real handoff. Plain JUnit, no mock libraries (Postmark convention).
 */
class OptimisticMmsMatcherTest {

    private fun cand(id: Long, body: String, ts: Long) = OptimisticCandidate(id, body, ts)

    @Test
    fun `body and window match is found`() {
        assertEquals(
            -100L,
            pickOptimisticMatch(
                realBody = "hello",
                realTimestampMs = 10_000L,
                candidates = listOf(cand(-100L, "hello", 10_500L))
            )
        )
    }

    @Test
    fun `body mismatch returns null`() {
        assertNull(
            pickOptimisticMatch(
                realBody = "hello",
                realTimestampMs = 10_000L,
                candidates = listOf(cand(-100L, "goodbye", 10_000L))
            )
        )
    }

    @Test
    fun `equal bodies outside the window return null`() {
        assertNull(
            pickOptimisticMatch(
                realBody = "hello",
                realTimestampMs = 0L,
                candidates = listOf(cand(-100L, "hello", OPTIMISTIC_MMS_MATCH_WINDOW_MS + 1))
            )
        )
    }

    @Test
    fun `closest timestamp wins among two qualifiers`() {
        assertEquals(
            -200L,
            pickOptimisticMatch(
                realBody = "hi",
                realTimestampMs = 10_000L,
                candidates = listOf(
                    cand(-100L, "hi", 10_800L),   // Δ 800
                    cand(-200L, "hi", 10_100L)    // Δ 100 — closer
                )
            )
        )
    }

    @Test
    fun `exact timestamp tie is broken by the larger id`() {
        assertEquals(
            -100L,
            pickOptimisticMatch(
                realBody = "hi",
                realTimestampMs = 10_000L,
                candidates = listOf(
                    cand(-200L, "hi", 9_500L),    // Δ 500, id -200
                    cand(-100L, "hi", 10_500L)    // Δ 500, id -100 (larger)
                )
            )
        )
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(pickOptimisticMatch("hello", 10_000L, emptyList()))
    }

    @Test
    fun `attachment-only memo with blank bodies matches on the window alone`() {
        assertEquals(
            -100L,
            pickOptimisticMatch(
                realBody = "",
                realTimestampMs = 10_000L,
                candidates = listOf(cand(-100L, "", 10_200L))
            )
        )
    }

    @Test
    fun `whitespace-only body matches an empty real body after trimming`() {
        assertEquals(
            -100L,
            pickOptimisticMatch(
                realBody = "",
                realTimestampMs = 10_000L,
                candidates = listOf(cand(-100L, "   ", 10_000L))
            )
        )
    }
}
