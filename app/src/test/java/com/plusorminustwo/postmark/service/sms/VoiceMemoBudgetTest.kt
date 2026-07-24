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

    // ── Carrier-aware duration cap (effectiveVoiceMemoCapMs) ──────────────────
    // min(fixed, live) can only ever shorten the cap — a memo must never become
    // unsendable just because the SIM changed to a stricter carrier.

    @Test fun `generous live carrier budget leaves the fixed cap unchanged`() {
        val fixed   = MmsManagerWrapper.MAX_VOICE_MEMO_DURATION_MS
        val bitrate = MmsManagerWrapper.VOICE_MEMO_BITRATE_BPS
        val cap = effectiveVoiceMemoCapMs(fixed, 2_000_000, bitrate)
        assertEquals(fixed, cap)
    }

    @Test fun `strict live carrier budget shortens the cap to match maxVoiceMemoDurationMs`() {
        val fixed     = MmsManagerWrapper.MAX_VOICE_MEMO_DURATION_MS
        val bitrate   = MmsManagerWrapper.VOICE_MEMO_BITRATE_BPS
        val liveBytes = 300_000
        val cap = effectiveVoiceMemoCapMs(fixed, liveBytes, bitrate)
        assertTrue("cap $cap should be strictly shorter than fixed $fixed", cap < fixed)
        assertEquals(
            maxVoiceMemoDurationMs(liveBytes - MmsManagerWrapper.PDU_OVERHEAD_BYTES, bitrate),
            cap
        )
    }

    @Test fun `effective cap never exceeds the fixed cap across carrier budgets`() {
        val fixed   = MmsManagerWrapper.MAX_VOICE_MEMO_DURATION_MS
        val bitrate = MmsManagerWrapper.VOICE_MEMO_BITRATE_BPS
        listOf(300_000, 860_160, 2_000_000).forEach { liveBytes ->
            val cap = effectiveVoiceMemoCapMs(fixed, liveBytes, bitrate)
            assertTrue("cap $cap for budget $liveBytes must not exceed fixed $fixed", cap <= fixed)
        }
    }

    // ── Cache filename predicate (shared by orphan sweep + delete paths) ──────

    @Test fun `sweep recognizes all three outgoing cache patterns`() {
        assertTrue(MmsManagerWrapper.isOutgoingCacheFileName("mms_attach_-123.bin"))
        assertTrue(MmsManagerWrapper.isOutgoingCacheFileName("mms_attach_-123_2.bin"))
        assertTrue(MmsManagerWrapper.isOutgoingCacheFileName("voice_memo_1752685000000.m4a"))
        assertTrue(MmsManagerWrapper.isOutgoingCacheFileName("camera_capture_1752685000000.jpg"))
    }

    @Test fun `sweep never touches files it does not own`() {
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("sync_log.txt"))
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("voice_memo_123.bin"))
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("mms_attach_123.m4a"))
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("profile.jpg"))
        // Camera-capture-specific near-misses: wrong suffix, wrong prefix, and a
        // foreign name that merely shares the "camera" word.
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("camera_capture_123.png"))
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("photo_123.jpg"))
        assertFalse(MmsManagerWrapper.isOutgoingCacheFileName("camera_roll_123.jpg"))
    }

    // ── Camera capture filename shape ──────────────────────────────────────────
    // cameraCaptureFile(context, epochMs) itself needs a real Context, so it isn't
    // called directly here (same constraint that already keeps voiceMemoCacheFile
    // untested below the Context boundary) — instead these assert that the naming
    // convention it must follow (prefix + epochMs + suffix) is exactly what
    // isOutgoingCacheFileName recognizes, and that two captures a millisecond apart
    // never collide (dedupe in appendAttachments is by uri string).

    @Test fun `camera capture filename shape matches what the sweep predicate accepts`() {
        val epochMs = 1752685000000L
        val name = "${MmsManagerWrapper.CAMERA_CAPTURE_FILE_PREFIX}$epochMs${MmsManagerWrapper.CAMERA_CAPTURE_FILE_SUFFIX}"
        assertEquals("camera_capture_1752685000000.jpg", name)
        assertTrue(MmsManagerWrapper.isOutgoingCacheFileName(name))
    }

    @Test fun `distinct epochMs values never collide in the minted filename`() {
        val a = "${MmsManagerWrapper.CAMERA_CAPTURE_FILE_PREFIX}1000${MmsManagerWrapper.CAMERA_CAPTURE_FILE_SUFFIX}"
        val b = "${MmsManagerWrapper.CAMERA_CAPTURE_FILE_PREFIX}1001${MmsManagerWrapper.CAMERA_CAPTURE_FILE_SUFFIX}"
        assertFalse(a == b)
    }
}
