package com.plusorminustwo.postmark.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [reactionPillTopPx].
 *
 * The function chooses between two placements:
 *  - **Above** the bubble: `bubbleTopY - pillHeightPx - gapPx`
 *    when there is enough space above (i.e. bubbleTopY > minTopPx + pillHeightPx + gapPx)
 *  - **Below** the bubble: `bubbleTopY + gapPx` as a fallback
 *
 * Uses concrete pixel values (dp-to-px conversion is Compose-specific; tests work in raw px).
 * Default reference values: pillHeightPx=64f, gapPx=8f, minTopPx=80f.
 */
class ReactionPillPositionTest {

    // Default geometry matching the composable's dp values at a nominal 1:1 density
    private val pill = 64f
    private val gap  = 8f
    private val min  = 80f   // action bar + status bar clearance

    // The threshold above which "place above" fires: min + pill + gap = 152f
    private val threshold = min + pill + gap  // 152f

    private fun pos(bubbleY: Float) = reactionPillTopPx(bubbleY, pill, gap, min)

    // ── above-bubble placement ───────────────────────────────────────────────

    @Test
    fun `bubble well below top places pill above bubble`() {
        val bubbleY = 400f   // clearly > 152
        assertEquals(bubbleY - pill - gap, pos(bubbleY), 0.001f)
    }

    @Test
    fun `bubble at exactly threshold plus 1 places pill above`() {
        val bubbleY = threshold + 1f
        assertEquals(bubbleY - pill - gap, pos(bubbleY), 0.001f)
    }

    @Test
    fun `above placement yields correct numeric value`() {
        // bubbleY=300: expected = 300 - 64 - 8 = 228
        assertEquals(228f, pos(300f), 0.001f)
    }

    // ── below-bubble placement (fallback) ────────────────────────────────────

    @Test
    fun `bubble at exactly threshold places pill below`() {
        // bubbleTopY > threshold is required; equality falls to below
        val bubbleY = threshold
        assertEquals(bubbleY + gap, pos(bubbleY), 0.001f)
    }

    @Test
    fun `bubble near top of screen places pill below`() {
        val bubbleY = 0f
        assertEquals(bubbleY + gap, pos(bubbleY), 0.001f)
    }

    @Test
    fun `bubble just under threshold places pill below`() {
        val bubbleY = threshold - 1f
        assertEquals(bubbleY + gap, pos(bubbleY), 0.001f)
    }

    @Test
    fun `below placement yields correct numeric value`() {
        // bubbleY=100: expected = 100 + 8 = 108
        assertEquals(108f, pos(100f), 0.001f)
    }

    // ── boundary precision ────────────────────────────────────────────────────

    @Test
    fun `above and below placements never overlap for any bubbleY`() {
        // above result is always lower on screen (larger y) than below result for same bubble
        // Actually: above = bubbleY - pill - gap; below = bubbleY + gap
        // This test just verifies the correct branch is taken across a range
        for (y in 0..600 step 10) {
            val result = pos(y.toFloat())
            val expectedAbove = y - pill - gap
            val expectedBelow = y + gap
            val shouldBeAbove = y > threshold
            if (shouldBeAbove) {
                assertEquals("y=$y should be above", expectedAbove, result, 0.001f)
            } else {
                assertEquals("y=$y should be below", expectedBelow, result, 0.001f)
            }
        }
    }

    // ── custom geometry ───────────────────────────────────────────────────────

    @Test
    fun `custom pill height is respected`() {
        // pill=100, gap=10, min=50 → threshold=160; bubble=200 → above: 200-100-10=90
        assertEquals(90f, reactionPillTopPx(200f, 100f, 10f, 50f), 0.001f)
    }

    @Test
    fun `custom gap is respected in below placement`() {
        // pill=64, gap=20, min=80 → threshold=164; bubble=100 → below: 100+20=120
        assertEquals(120f, reactionPillTopPx(100f, 64f, 20f, 80f), 0.001f)
    }
}
