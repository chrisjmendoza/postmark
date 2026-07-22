package com.plusorminustwo.postmark.domain.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * Geometry + codec for [BackgroundPlacement] / [BackgroundPlacementMath], pinned against the
 * reported repro: a 3000×4000 portrait photo in a 1080×2340 viewport. Plain JUnit4 (matching
 * [ContactPaletteTest]); tolerance 1e-3 on all float comparisons.
 */
class BackgroundPlacementTest {

    // Reported repro fixture: 3000×4000 image, 1080×2340 viewport.
    private val iw = 3000
    private val ih = 4000
    private val vw = 1080
    private val vh = 2340
    private val tol = 1e-3f

    // ── Scales ─────────────────────────────────────────────────────────────────

    @Test
    fun `fill fit and min zoom for the repro fixture`() {
        assertEquals(0.585f, BackgroundPlacementMath.fillScale(iw, ih, vw, vh), tol)
        assertEquals(0.36f, BackgroundPlacementMath.fitScale(iw, ih, vw, vh), tol)
        assertEquals(0.61538f, BackgroundPlacementMath.minZoom(iw, ih, vw, vh), tol)
    }

    // ── FILL clamp ─────────────────────────────────────────────────────────────

    @Test
    fun `at FILL the vertical axis locks to center and cx clamps to the horizontal window`() {
        // eh = 4000 exactly == image height → y is locked (bands symmetric).
        val (cxLow, cy) = BackgroundPlacementMath.clampCenter(iw, ih, vw, vh, 1f, 0f, 0f)
        assertEquals(0.5f, cy, tol)
        assertEquals(0.30769f, cxLow, tol)

        val (cxHigh, _) = BackgroundPlacementMath.clampCenter(iw, ih, vw, vh, 1f, 1f, 0f)
        assertEquals(0.69231f, cxHigh, tol)
    }

    // ── applyGesture ───────────────────────────────────────────────────────────

    @Test
    fun `panning right decreases cx by pan over s times iw`() {
        val result = BackgroundPlacementMath.applyGesture(
            BackgroundPlacement.FILL, panXpx = 100f, panYpx = 0f, zoomFactor = 1f, iw, ih, vw, vh
        )
        // cx' = 0.5 - 100 / (0.585 * 3000) = 0.44302; y stays locked at 0.5.
        assertTrue("pan right should decrease cx", result.cx < 0.5f)
        assertEquals(0.44302f, result.cx, tol)
        assertEquals(0.5f, result.cy, tol)
    }

    @Test
    fun `zoom coerces into the min zoom and max zoom bounds`() {
        val tooSmall = BackgroundPlacementMath.applyGesture(
            BackgroundPlacement.FILL, 0f, 0f, zoomFactor = 0.1f, iw, ih, vw, vh
        )
        assertEquals(BackgroundPlacementMath.minZoom(iw, ih, vw, vh), tooSmall.zoom, tol)

        val tooBig = BackgroundPlacementMath.applyGesture(
            BackgroundPlacement.FILL, 0f, 0f, zoomFactor = 100f, iw, ih, vw, vh
        )
        assertEquals(BackgroundPlacement.MAX_ZOOM, tooBig.zoom, tol)
    }

    @Test
    fun `at zoom 0-8 x is pannable but y is locked`() {
        val result = BackgroundPlacementMath.applyGesture(
            BackgroundPlacement(0.5f, 0.5f, 0.8f), panXpx = 100f, panYpx = 100f, zoomFactor = 1f,
            iw, ih, vw, vh
        )
        // ew < iw → x moves; eh (5000) > ih → y locked to 0.5.
        assertTrue("x should be pannable at zoom 0.8", result.cx < 0.5f)
        assertEquals(0.42877f, result.cx, tol)
        assertEquals(0.5f, result.cy, tol)
    }

    // ── FIT bake (bands) ─────────────────────────────────────────────────────────

    @Test
    fun `FIT visible rect overhangs the image on the banded axis and bakes to bands`() {
        val fit = BackgroundPlacement(0.5f, 0.5f, BackgroundPlacementMath.minZoom(iw, ih, vw, vh))
        val rect = BackgroundPlacementMath.visibleRectPx(iw, ih, vw, vh, fit)
        // Taller than the image on the y (banded) axis: t < 0 and b > ih.
        assertTrue("top overhang", rect[1] < 0f)
        assertTrue("bottom overhang", rect[3] > ih.toFloat())

        val geo = BackgroundPlacementMath.bakeGeometry(iw, ih, vw, vh, fit)
        assertTrue("top band", geo.dstT > 0)
        assertTrue("bottom band", geo.dstB < geo.outH)
        // Source clamped back into the image.
        assertEquals(0, geo.srcT)
        assertEquals(ih, geo.srcB)
    }

    // ── FILL bake (full cover) ───────────────────────────────────────────────────

    @Test
    fun `FILL bake fills the whole canvas at the viewport aspect with no bands`() {
        val geo = BackgroundPlacementMath.bakeGeometry(iw, ih, vw, vh, BackgroundPlacement.FILL)

        assertEquals(1440, max(geo.outW, geo.outH))
        // Output aspect matches the viewport within ±1 px.
        assertTrue(
            "aspect within 1px",
            kotlin.math.abs(geo.outW - geo.outH * vw.toFloat() / vh) <= 1f
        )
        // Source is the centered vertical strip ≈ (577, 0, 2423, 4000).
        assertEquals(577, geo.srcL)
        assertEquals(0, geo.srcT)
        assertEquals(2423, geo.srcR)
        assertEquals(4000, geo.srcB)
        // Dest covers the full canvas — no black bands.
        assertTrue(
            "dst covers canvas",
            geo.dstL <= 0 && geo.dstT <= 0 && geo.dstR >= geo.outW && geo.dstB >= geo.outH
        )
    }

    // ── editorTransform ──────────────────────────────────────────────────────────

    @Test
    fun `editorTransform is identity translate at FILL and shifts with cx`() {
        val s = BackgroundPlacementMath.fillScale(iw, ih, vw, vh)
        val atFill = BackgroundPlacementMath.editorTransform(iw, ih, vw, vh, BackgroundPlacement.FILL)
        assertEquals(0.585f, atFill[0], tol)
        assertEquals(0f, atFill[1], tol)
        assertEquals(0f, atFill[2], tol)

        val shifted = BackgroundPlacementMath.editorTransform(
            iw, ih, vw, vh, BackgroundPlacement(0.6f, 0.5f, 1f)
        )
        assertEquals(-0.1f * iw * s, shifted[1], 0.05f)
    }

    // ── Codec ─────────────────────────────────────────────────────────────────────

    @Test
    fun `encode then decode round-trips`() {
        val p = BackgroundPlacement(0.3f, 0.7f, 1.5f)
        assertEquals(p, BackgroundPlacement.decode(BackgroundPlacement.encode(p)))
    }

    @Test
    fun `decode rejects garbage wrong version and wrong field count`() {
        assertNull(BackgroundPlacement.decode("garbage"))
        assertNull(BackgroundPlacement.decode("v2;0.5;0.5;1"))
        assertNull(BackgroundPlacement.decode("v1;0.5;0.5"))
    }
}
