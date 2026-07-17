package com.plusorminustwo.postmark.service.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [maxVoiceMemoDurationMs] — the pure function that derives the recording
 * duration cap from the MMS byte budget — and the cache-filename predicate the
 * orphan sweep and delete paths share.
 *
 * The invariant that matters: a memo recorded right up to the cap must encode to
 * fewer bytes than the budget, because audio is non-compressible in
 * [allocateAttachmentBudgets] — an over-budget memo doesn't get shrunk, it fails
 * the send.
 */
class VoiceMemoBudgetTest {

    @Test fun `cap times bitrate stays under the byte budget`() {
        val budgetBytes = 855_160  // DEFAULT_MAX_MMS_BYTES - PDU_OVERHEAD_BYTES
        val bitrate     = MmsManagerWrapper.VOICE_MEMO_BITRATE_BPS
        val capMs       = maxVoiceMemoDurationMs(budgetBytes, bitrate)
        val encodedBytes = capMs / 1000.0 * bitrate / 8
        assertTrue("encoded size $encodedBytes must fit budget", encodedBytes < budgetBytes)
    }

    @Test fun `default cap is a sane speech-memo length`() {
        // ~102 s at 64 kbps against the 860 KB default budget. The exact value may
        // drift with the constants; the guardrails are what matter — long enough to
        // be useful, short enough to always fit the strictest carrier.
        val cap = MmsManagerWrapper.MAX_VOICE_MEMO_DURATION_MS
        assertTrue("cap $cap too short", cap >= 60_000)
        assertTrue("cap $cap too long", cap <= 180_000)
    }

    @Test fun `cap is floored to whole seconds for an exact timer display`() {
        assertEquals(0L, MmsManagerWrapper.MAX_VOICE_MEMO_DURATION_MS % 1000)
    }

    @Test fun `larger budget or lower bitrate never shortens the cap`() {
        val base = maxVoiceMemoDurationMs(800_000, 64_000)
        assertTrue(maxVoiceMemoDurationMs(900_000, 64_000) >= base)
        assertTrue(maxVoiceMemoDurationMs(800_000, 48_000) >= base)
    }

    @Test fun `degenerate inputs yield a zero cap instead of garbage`() {
        assertEquals(0L, maxVoiceMemoDurationMs(0, 64_000))
        assertEquals(0L, maxVoiceMemoDurationMs(-1, 64_000))
        assertEquals(0L, maxVoiceMemoDurationMs(800_000, 0))
        assertEquals(0L, maxVoiceMemoDurationMs(800_000, -1))
    }

    // ── Cache filename predicate (shared by orphan sweep + delete paths) ──────

    @Test fun `sweep recognizes both outgoing cache patterns`() {
        assertTrue(MmsManagerWrapper.isOutgoingCacheFileName("mms_attach_-123.bin"))
        assertTrue(MmsManagerWrapper.isOutgoingCacheFileName("mms_attach_-123_2.bin"))
        assertTrue(MmsManagerWrapper.isOutgoingCacheFileName("voice_memo_1752685000000.m4a"))
    }

    @Test fun `sweep never touches files it does not own`() {
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("sync_log.txt"))
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("voice_memo_123.bin"))
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("mms_attach_123.m4a"))
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("profile.jpg"))
    }
}
