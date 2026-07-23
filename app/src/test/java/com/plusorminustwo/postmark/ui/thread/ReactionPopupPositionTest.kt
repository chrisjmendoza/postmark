package com.plusorminustwo.postmark.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [reactionPopupTopPx].
 *
 * The function positions the anchored emoji reaction popup:
 *  - **Below** the bubble (`bubbleBottomY + gapPx`) — preferred, when the whole popup fits
 *    above `maxBottomPx`.
 *  - **Above** the bubble (`bubbleTopY − gapPx − popupHeightPx`) — when below would fall past
 *    `maxBottomPx` (bubble near the screen bottom, popup would land under the nav bar).
 *  - **Clamped** into `[minTopPx, maxBottomPx − popupHeightPx]` — last resort when it fits neither.
 *
 * Works in raw px (dp-to-px conversion is Compose-specific).
 */
class ReactionPopupPositionTest {

    // Reference geometry: gap 8px, popup 200px tall, viewport band [100, 1000].
    private val gap    = 8f
    private val height = 200f
    private val minTop = 100f
    private val maxBot = 1000f

    private fun pos(
        bubbleTopY: Float,
        bubbleBottomY: Float,
        popupHeightPx: Float = height,
        minTopPx: Float = minTop,
        maxBottomPx: Float = maxBot
    ) = reactionPopupTopPx(bubbleTopY, bubbleBottomY, popupHeightPx, gap, minTopPx, maxBottomPx)

    // ── Prefer below ────────────────────────────────────────────────────────────

    @Test
    fun `bubble in mid-screen places popup just below it`() {
        // below = 400 + 8 = 408; 408 + 200 = 608 <= 1000 → below
        assertEquals(408f, pos(bubbleTopY = 340f, bubbleBottomY = 400f), 0.001f)
    }

    @Test
    fun `popup that exactly fits below stays below`() {
        // below + height == maxBottomPx (800 + 200 = 1000) → still below (<=)
        val bottom = maxBot - height - gap // 792
        assertEquals(bottom + gap, pos(bubbleTopY = 700f, bubbleBottomY = bottom), 0.001f)
    }

    // ── Flip above ──────────────────────────────────────────────────────────────

    @Test
    fun `bubble near screen bottom flips popup above the bubble`() {
        // below = 858; 858 + 200 = 1058 > 1000 → flip; above = 780 - 8 - 200 = 572
        assertEquals(572f, pos(bubbleTopY = 780f, bubbleBottomY = 850f), 0.001f)
    }

    @Test
    fun `larger nav inset flips a popup that would otherwise fit below`() {
        val top = 740f
        val bottom = 800f
        // below = 808; below + 180 = 988
        // small nav inset (maxBottomPx = 1000): 988 <= 1000 → below = 808
        assertEquals(808f, pos(top, bottom, popupHeightPx = 180f, maxBottomPx = 1000f), 0.001f)
        // larger nav inset (maxBottomPx = 950): 988 > 950 → flip above = 740 - 8 - 180 = 552
        assertEquals(552f, pos(top, bottom, popupHeightPx = 180f, maxBottomPx = 950f), 0.001f)
    }

    // ── Clamp when neither direction fits ─────────────────────────────────────────

    @Test
    fun `popup taller than both gaps clamps to the top bound`() {
        // Tight band [100, 300], popup 250 tall. bubbleBottom=280 → below=288; 288+250=538>300
        // → flip; above = 270 - 8 - 250 = 12 < 100 → clamp into [100, max(100, 300-250=50)=100]
        assertEquals(
            100f,
            pos(bubbleTopY = 270f, bubbleBottomY = 280f, popupHeightPx = 250f, minTopPx = 100f, maxBottomPx = 300f),
            0.001f
        )
    }

    // ── Nav-bar inset is respected via maxBottomPx ────────────────────────────────

    @Test
    fun `bottom bound accounts for the nav inset boundary exactly`() {
        // below + height == maxBottomPx exactly → stays below
        val maxBottom = 900f
        val bottom = maxBottom - height - gap // 692
        assertEquals(bottom + gap, pos(bubbleTopY = 600f, bubbleBottomY = bottom, maxBottomPx = maxBottom), 0.001f)
        // One pixel lower bubble → below overflows the nav boundary → flips above
        val bottomLower = bottom + 1f
        val expectedAbove = 601f - gap - height // bubbleTop 601 → 393
        assertEquals(expectedAbove, pos(bubbleTopY = 601f, bubbleBottomY = bottomLower, maxBottomPx = maxBottom), 0.001f)
    }

    // ── First frame (popup height not yet measured) ──────────────────────────────

    @Test
    fun `unmeasured popup renders at the below position`() {
        // popupHeightPx = 0 → below always fits (below + 0 <= maxBottomPx) even near the bottom;
        // self-corrects once onSizeChanged reports the real height.
        assertEquals(858f, pos(bubbleTopY = 780f, bubbleBottomY = 850f, popupHeightPx = 0f), 0.001f)
    }
}
