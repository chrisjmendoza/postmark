package com.plusorminustwo.postmark.service.sms

import com.plusorminustwo.postmark.service.sms.MultipartSendTracker.Decision
import com.plusorminustwo.postmark.service.sms.MultipartSendTracker.Outcome
import com.plusorminustwo.postmark.service.sms.MultipartSendTracker.RecoveryPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MultipartSendTracker] — the pure aggregator that collapses N per-part
 * SMS "sent" callbacks into one correct final status. The class has no Android deps, so it
 * is exercised directly.
 *
 * The invariants under test: MarkSent only once ALL parts are Ok; the first KnownFailure is
 * terminal so a later Ok can never overwrite FAILED back to SENT; ambiguous parts block
 * MarkSent forever (PENDING); duplicate part re-fires don't double-count; interleaved
 * messages stay independent; terminal markers don't grow the map without bound.
 */
class MultipartSendTrackerTest {

    private fun tracker() = MultipartSendTracker()

    @Test
    fun `single-part OK marks sent`() {
        val t = tracker()
        assertEquals(Decision.MarkSent(null), t.record(1L, partIndex = 0, partCount = 1, outcome = Outcome.Ok))
    }

    @Test
    fun `all parts OK out of order marks sent only on the final one`() {
        val t = tracker()
        // Three parts arriving 2, 0, 1.
        assertEquals(Decision.None, t.record(1L, 2, 3, Outcome.Ok))
        assertEquals(Decision.None, t.record(1L, 0, 3, Outcome.Ok))
        assertEquals(Decision.MarkSent(null), t.record(1L, 1, 3, Outcome.Ok))
    }

    @Test
    fun `failure then later OK marks failed once then None — never sent`() {
        val t = tracker()
        assertEquals(Decision.MarkFailed, t.record(1L, 0, 3, Outcome.KnownFailure))
        // The remaining parts still fire; none may resurrect the message to SENT.
        assertEquals(Decision.None, t.record(1L, 1, 3, Outcome.Ok))
        assertEquals(Decision.None, t.record(1L, 2, 3, Outcome.Ok))
    }

    @Test
    fun `OK then failure marks failed`() {
        val t = tracker()
        assertEquals(Decision.None, t.record(1L, 0, 3, Outcome.Ok))
        assertEquals(Decision.MarkFailed, t.record(1L, 1, 3, Outcome.KnownFailure))
        assertEquals(Decision.None, t.record(1L, 2, 3, Outcome.Ok))
    }

    @Test
    fun `second failure yields None — MarkFailed emitted exactly once`() {
        val t = tracker()
        assertEquals(Decision.MarkFailed, t.record(1L, 0, 2, Outcome.KnownFailure))
        assertEquals(Decision.None, t.record(1L, 1, 2, Outcome.KnownFailure))
    }

    @Test
    fun `ambiguous part blocks MarkSent forever`() {
        val t = tracker()
        assertEquals(Decision.None, t.record(1L, 0, 3, Outcome.Ok))
        assertEquals(Decision.None, t.record(1L, 1, 3, Outcome.Ambiguous))
        // Part 2 Ok: still only 2 distinct Ok parts, never reaches 3 → stays PENDING.
        assertEquals(Decision.None, t.record(1L, 2, 3, Outcome.Ok))
        // Even re-firing the ambiguous part (still ambiguous) can't complete it.
        assertEquals(Decision.None, t.record(1L, 1, 3, Outcome.Ambiguous))
    }

    @Test
    fun `duplicate partIndex re-fire does not double-count`() {
        val t = tracker()
        assertEquals(Decision.None, t.record(1L, 0, 2, Outcome.Ok))
        // Part 0 re-fires (PendingIntents can) — must not count as the second part.
        assertEquals(Decision.None, t.record(1L, 0, 2, Outcome.Ok))
        // Only when the genuine second part arrives does it complete.
        assertEquals(Decision.MarkSent(null), t.record(1L, 1, 2, Outcome.Ok))
    }

    @Test
    fun `two interleaved messages are tracked independently`() {
        val t = tracker()
        assertEquals(Decision.None, t.record(1L, 0, 2, Outcome.Ok))
        assertEquals(Decision.None, t.record(2L, 0, 2, Outcome.Ok))
        assertEquals(Decision.MarkFailed, t.record(2L, 1, 2, Outcome.KnownFailure))
        // Message 1 is unaffected by message 2's failure.
        assertEquals(Decision.MarkSent(null), t.record(1L, 1, 2, Outcome.Ok))
    }

    @Test
    fun `MarkSent carries the recovery payload stashed from the last part regardless of completion order`() {
        val t = tracker()
        val payload = RecoveryPayload("+12065550100", "the whole message body")
        // The last part (carrying the recovery payload) reports first, out of order.
        assertEquals(Decision.None, t.record(1L, 1, 2, Outcome.Ok, recovery = payload))
        // Part 0 (no payload) completes the message — MarkSent must still surface the stash.
        assertEquals(Decision.MarkSent(payload), t.record(1L, 0, 2, Outcome.Ok))
    }

    @Test
    fun `MarkSent recovery is null when no part carried a payload`() {
        val t = tracker()
        assertEquals(Decision.None, t.record(1L, 0, 2, Outcome.Ok))
        assertEquals(Decision.MarkSent(null), t.record(1L, 1, 2, Outcome.Ok))
    }

    @Test
    fun `completed part re-fire after MarkSent yields None`() {
        val t = tracker()
        assertEquals(Decision.MarkSent(null), t.record(1L, 0, 1, Outcome.Ok))
        // The single part's PendingIntent re-fires — must not emit MarkSent again.
        assertEquals(Decision.None, t.record(1L, 0, 1, Outcome.Ok))
    }

    @Test
    fun `reset lets a re-send after failure aggregate fresh`() {
        val t = tracker()
        // First send fails → terminal FAILED marker retained for this key.
        assertEquals(Decision.MarkFailed, t.record(5L, 0, 1, Outcome.KnownFailure))
        // The offline send queue re-sends the SAME key. Without a reset the retained
        // terminal marker swallows every part as already-decided — the message would be
        // stuck PENDING forever.
        assertEquals(Decision.None, t.record(5L, 0, 1, Outcome.Ok))
        // reset() clears the marker; the re-send now completes normally.
        t.reset(5L)
        assertEquals(Decision.MarkSent(null), t.record(5L, 0, 1, Outcome.Ok))
    }

    @Test
    fun `reset of an unknown key is a harmless no-op`() {
        val t = tracker()
        t.reset(999L) // never recorded — must not throw or create state
        assertEquals(Decision.MarkSent(null), t.record(999L, 0, 1, Outcome.Ok))
    }

    @Test
    fun `terminal markers are bounded by the LRU cap`() {
        val t = tracker()
        val overflow = MultipartSendTracker.MAX_TRACKED_KEYS + 50
        // Each key completes immediately (single part) leaving a terminal marker behind.
        for (key in 1..overflow) {
            t.record(key.toLong(), 0, 1, Outcome.Ok)
        }
        assertTrue(
            "expected map bounded by ${MultipartSendTracker.MAX_TRACKED_KEYS}, was ${t.trackedKeyCount()}",
            t.trackedKeyCount() <= MultipartSendTracker.MAX_TRACKED_KEYS
        )
    }
}
