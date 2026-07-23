package com.plusorminustwo.postmark.domain.scrollcapture

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollCaptureMathTest {

    // ── scrollDeltaForCaptureTop ──────────────────────────────────────────────

    @Test
    fun normalList_deltaMatchesCaptureTopSign() {
        assertEquals(500, ScrollCaptureMath.scrollDeltaForCaptureTop(500, reverseLayout = false))
        assertEquals(-500, ScrollCaptureMath.scrollDeltaForCaptureTop(-500, reverseLayout = false))
        assertEquals(0, ScrollCaptureMath.scrollDeltaForCaptureTop(0, reverseLayout = false))
    }

    @Test
    fun reverseLayout_flipsTheSign() {
        // The load-bearing flip: a request for content below (positive) becomes a
        // negative scrollBy on a reverseLayout list, and vice-versa.
        assertEquals(-500, ScrollCaptureMath.scrollDeltaForCaptureTop(500, reverseLayout = true))
        assertEquals(500, ScrollCaptureMath.scrollDeltaForCaptureTop(-500, reverseLayout = true))
        assertEquals(0, ScrollCaptureMath.scrollDeltaForCaptureTop(0, reverseLayout = true))
    }

    // ── capturableRows ────────────────────────────────────────────────────────

    @Test
    fun bandShorterThanViewport_capturesFullBand() {
        assertEquals(300, ScrollCaptureMath.capturableRows(captureHeight = 300, viewportHeight = 800))
    }

    @Test
    fun bandTallerThanViewport_clampsToViewport() {
        assertEquals(800, ScrollCaptureMath.capturableRows(captureHeight = 1200, viewportHeight = 800))
    }

    @Test
    fun nonPositiveInputs_yieldZero() {
        assertEquals(0, ScrollCaptureMath.capturableRows(captureHeight = 0, viewportHeight = 800))
        assertEquals(0, ScrollCaptureMath.capturableRows(captureHeight = -100, viewportHeight = 800))
        assertEquals(0, ScrollCaptureMath.capturableRows(captureHeight = 300, viewportHeight = -5))
    }
}
