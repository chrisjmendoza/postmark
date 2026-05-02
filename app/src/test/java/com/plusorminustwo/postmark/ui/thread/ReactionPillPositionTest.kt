package com.plusorminustwo.postmark.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [reactionPillTopPx].
 *
 * The function places the emoji pill just below the bubble and clamps it so it never
 * goes off the bottom of the screen:
 *  - **Normal**: `bubbleBottomY + gapPx`
 *  - **Clamped** (bubble near screen bottom): `maxPillTopPx`
 *
 * Uses concrete pixel values (dp-to-px conversion is Compose-specific; tests work in raw px).
 * Default reference values: pillHeightPx=64f, gapPx=8f, maxPillTopPx=900f.
 */
class ReactionPillPositionTest {

    // Default geometry
    private val pill = 64f
    private val gap  = 8f
    private val max  = 900f  // maxPillTopPx — screen height minus pill and margin

    private fun pos(bubbleBottomY: Float) = reactionPillTopPx(bubbleBottomY, pill, gap, max)

    // ── normal below-bubble placement ────────────────────────────────────────

    @Test
    fun `bubble in middle of screen places pill just below it`() {
        val bottomY = 400f
        assertEquals(bottomY + gap, pos(bottomY), 0.001f)
    }

    @Test
    fun `bubble near top of screen places pill just below it`() {
        val bottomY = 100f
        assertEquals(bottomY + gap, pos(bottomY), 0.001f)
    }

    @Test
    fun `below placement yields correct numeric value`() {
        // bottomY=300: expected = 300 + 8 = 308
        assertEquals(308f, pos(300f), 0.001f)
    }

    // ── clamped placement (bubble near bottom of screen) ─────────────────────

    @Test
    fun `bubble near screen bottom clamps pill to maxPillTopPx`() {
        // bubbleBottomY + gap = 920 > max=900, so result is clamped to 900
        val bottomY = 912f
        assertEquals(max, pos(bottomY), 0.001f)
    }

    @Test
    fun `pill exactly at max boundary is not clamped`() {
        // bubbleBottomY + gap = 900 → exactly max, minOf returns max
        val bottomY = max - gap
        assertEquals(max, pos(bottomY), 0.001f)
    }

    @Test
    fun `pill one pixel above max is not clamped`() {
        val bottomY = max - gap - 1f
        assertEquals(bottomY + gap, pos(bottomY), 0.001f)
    }

    // ── boundary precision across range ──────────────────────────────────────

    @Test
    fun `result is always less than or equal to maxPillTopPx`() {
        for (y in 0..1000 step 20) {
            val result = pos(y.toFloat())
            assert(result <= max) { "y=$y: result=$result exceeded max=$max" }
        }
    }

    // ── custom geometry ───────────────────────────────────────────────────────

    @Test
    fun `custom gap is respected`() {
        // bottomY=300, gap=20, max=900 → 300+20=320
        assertEquals(320f, reactionPillTopPx(300f, 64f, 20f, 900f), 0.001f)
    }

    @Test
    fun `custom maxPillTopPx clamps correctly`() {
        // bottomY=200, gap=10, max=100 → desired=210 > max=100, clamped to 100
        assertEquals(100f, reactionPillTopPx(200f, 64f, 10f, 100f), 0.001f)
    }
}
