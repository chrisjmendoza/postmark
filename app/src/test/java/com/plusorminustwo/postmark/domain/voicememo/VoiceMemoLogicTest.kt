package com.plusorminustwo.postmark.domain.voicememo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the voice memo state machine and gesture math. The transition table is
 * exercised exhaustively — every (phase, event) pair — because the UI drives it from
 * raw pointer events, where "impossible" sequences (a RELEASE after latching, a
 * CAP_REACHED racing a user stop) are just rare, not impossible.
 */
class VoiceMemoLogicTest {

    private fun t(phase: VoiceMemoPhase, event: VoiceMemoEvent) = voiceMemoTransition(phase, event)

    // ── IDLE ──────────────────────────────────────────────────────────────────

    @Test fun `press from idle starts recording`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.HELD, VoiceMemoEffect.START),
            t(VoiceMemoPhase.IDLE, VoiceMemoEvent.PRESS)
        )
    }

    @Test fun `every non-press event in idle is a no-op`() {
        for (event in VoiceMemoEvent.entries.filter { it != VoiceMemoEvent.PRESS }) {
            assertEquals(
                "IDLE + $event",
                VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.NONE),
                t(VoiceMemoPhase.IDLE, event)
            )
        }
    }

    // ── HELD ──────────────────────────────────────────────────────────────────

    @Test fun `release while held stops and keeps — the memo goes to preview, never auto-sends`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_KEEP),
            t(VoiceMemoPhase.HELD, VoiceMemoEvent.RELEASE)
        )
    }

    @Test fun `slide-up latch while held locks without stopping`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.LOCKED, VoiceMemoEffect.NONE),
            t(VoiceMemoPhase.HELD, VoiceMemoEvent.LATCH_LOCK)
        )
    }

    @Test fun `cancel while held discards`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_DISCARD),
            t(VoiceMemoPhase.HELD, VoiceMemoEvent.CANCEL)
        )
    }

    @Test fun `duration cap while held stops and keeps`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_KEEP),
            t(VoiceMemoPhase.HELD, VoiceMemoEvent.CAP_REACHED)
        )
    }

    @Test fun `second press while held is a no-op`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.HELD, VoiceMemoEffect.NONE),
            t(VoiceMemoPhase.HELD, VoiceMemoEvent.PRESS)
        )
    }

    // ── LOCKED ────────────────────────────────────────────────────────────────

    @Test fun `release while locked is a no-op — that is the point of the latch`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.LOCKED, VoiceMemoEffect.NONE),
            t(VoiceMemoPhase.LOCKED, VoiceMemoEvent.RELEASE)
        )
    }

    @Test fun `stop tap while locked parks the take in preview — not attached yet`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.PREVIEW, VoiceMemoEffect.STOP_PREVIEW),
            t(VoiceMemoPhase.LOCKED, VoiceMemoEvent.STOP_TAP)
        )
    }

    @Test fun `cancel while locked discards`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_DISCARD),
            t(VoiceMemoPhase.LOCKED, VoiceMemoEvent.CANCEL)
        )
    }

    @Test fun `duration cap while locked also goes to preview`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.PREVIEW, VoiceMemoEffect.STOP_PREVIEW),
            t(VoiceMemoPhase.LOCKED, VoiceMemoEvent.CAP_REACHED)
        )
    }

    @Test fun `restart while locked re-records without leaving locked mode`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.LOCKED, VoiceMemoEffect.RESTART_RECORDING),
            t(VoiceMemoPhase.LOCKED, VoiceMemoEvent.RESTART)
        )
    }

    // ── PREVIEW ───────────────────────────────────────────────────────────────

    @Test fun `attach from preview queues the take and returns to idle`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.ATTACH_PREVIEW),
            t(VoiceMemoPhase.PREVIEW, VoiceMemoEvent.ATTACH)
        )
    }

    @Test fun `restart from preview drops the take and records again hands-free`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.LOCKED, VoiceMemoEffect.RESTART_RECORDING),
            t(VoiceMemoPhase.PREVIEW, VoiceMemoEvent.RESTART)
        )
    }

    @Test fun `cancel from preview discards the take`() {
        assertEquals(
            VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.DISCARD_PREVIEW),
            t(VoiceMemoPhase.PREVIEW, VoiceMemoEvent.CANCEL)
        )
    }

    @Test fun `stray recorder events in preview are no-ops`() {
        // The recorder is already stopped in PREVIEW; a racing CAP_REACHED (or any
        // leftover gesture event) must not disturb the held take.
        for (event in listOf(
            VoiceMemoEvent.PRESS, VoiceMemoEvent.LATCH_LOCK, VoiceMemoEvent.RELEASE,
            VoiceMemoEvent.STOP_TAP, VoiceMemoEvent.CAP_REACHED
        )) {
            assertEquals(
                "PREVIEW + $event",
                VoiceMemoTransition(VoiceMemoPhase.PREVIEW, VoiceMemoEffect.NONE),
                t(VoiceMemoPhase.PREVIEW, event)
            )
        }
    }

    @Test fun `recording only starts from an idle press or an explicit restart`() {
        for (phase in VoiceMemoPhase.entries) {
            for (event in VoiceMemoEvent.entries) {
                val transition = t(phase, event)
                val isIdlePress = phase == VoiceMemoPhase.IDLE && event == VoiceMemoEvent.PRESS
                val isRestart   = event == VoiceMemoEvent.RESTART &&
                    (phase == VoiceMemoPhase.LOCKED || phase == VoiceMemoPhase.PREVIEW)
                if (!isIdlePress) {
                    assertTrue(
                        "$phase + $event must not START",
                        transition.effect != VoiceMemoEffect.START
                    )
                }
                if (!isRestart) {
                    assertTrue(
                        "$phase + $event must not RESTART_RECORDING",
                        transition.effect != VoiceMemoEffect.RESTART_RECORDING
                    )
                }
            }
        }
    }

    // ── Gesture thresholds ────────────────────────────────────────────────────

    @Test fun `lock latches only on an upward drag past the threshold`() {
        assertTrue(shouldLatchLock(dragDeltaY = -80f, thresholdPx = 72f))
        assertTrue(shouldLatchLock(dragDeltaY = -72f, thresholdPx = 72f))
        assertFalse(shouldLatchLock(dragDeltaY = -71f, thresholdPx = 72f))
        // Downward drag never locks, however large.
        assertFalse(shouldLatchLock(dragDeltaY = 500f, thresholdPx = 72f))
    }

    @Test fun `cancel fires only on a leftward drag past the threshold`() {
        assertTrue(shouldCancelDrag(dragDeltaX = -100f, thresholdPx = 96f))
        assertTrue(shouldCancelDrag(dragDeltaX = -96f, thresholdPx = 96f))
        assertFalse(shouldCancelDrag(dragDeltaX = -95f, thresholdPx = 96f))
        // Rightward drag never cancels.
        assertFalse(shouldCancelDrag(dragDeltaX = 500f, thresholdPx = 96f))
    }

    @Test fun `non-positive thresholds never trigger`() {
        // A zero threshold would make every touch latch/cancel instantly.
        assertFalse(shouldLatchLock(-10f, 0f))
        assertFalse(shouldCancelDrag(-10f, 0f))
        assertFalse(shouldLatchLock(-10f, -1f))
        assertFalse(shouldCancelDrag(-10f, -1f))
    }

    // ── Keep-or-discard guard ─────────────────────────────────────────────────

    @Test fun `sub-minimum recordings are discarded`() {
        assertFalse(isMemoKeepable(elapsedMs = 0))
        assertFalse(isMemoKeepable(elapsedMs = MIN_VOICE_MEMO_DURATION_MS - 1))
        assertTrue(isMemoKeepable(elapsedMs = MIN_VOICE_MEMO_DURATION_MS))
        assertTrue(isMemoKeepable(elapsedMs = 10_000))
    }

    // ── Timer formatting ──────────────────────────────────────────────────────

    @Test fun `duration formats as minutes and zero-padded seconds`() {
        assertEquals("0:00", formatMemoDuration(0))
        assertEquals("0:00", formatMemoDuration(999))
        assertEquals("0:01", formatMemoDuration(1_000))
        assertEquals("0:59", formatMemoDuration(59_999))
        assertEquals("1:00", formatMemoDuration(60_000))
        assertEquals("1:42", formatMemoDuration(102_000))
        assertEquals("10:05", formatMemoDuration(605_000))
    }

    @Test fun `negative durations clamp to zero`() {
        assertEquals("0:00", formatMemoDuration(-5_000))
    }
}
