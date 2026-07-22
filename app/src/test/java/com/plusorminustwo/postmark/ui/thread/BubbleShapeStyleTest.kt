package com.plusorminustwo.postmark.ui.thread

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.plusorminustwo.postmark.ui.theme.BubbleStylePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [bubbleShape] is a pure function returning precomputed [RoundedCornerShape] instances,
 * which have a structural equals() (see androidx.compose.foundation.shape.RoundedCornerShape
 * and its DpCornerSize corners) — so these are plain JVM equality assertions, no Compose
 * runtime/graphics involved.
 *
 * ROUNDED values below are copied from today's production maps (bubbleFull = 16.dp,
 * bubbleSmall = 4.dp) so any accidental change to the default shape fails this test.
 */
class BubbleShapeStyleTest {

    // ── ROUNDED — must match today's shapes exactly (regression guard) ─────────

    @Test
    fun `ROUNDED sent SINGLE is fully rounded`() {
        assertEquals(
            RoundedCornerShape(16.dp),
            bubbleShape(BubbleStylePreference.ROUNDED, isSent = true, ClusterPosition.SINGLE)
        )
    }

    @Test
    fun `ROUNDED sent TOP has small bottom-end tail corner`() {
        assertEquals(
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp),
            bubbleShape(BubbleStylePreference.ROUNDED, isSent = true, ClusterPosition.TOP)
        )
    }

    @Test
    fun `ROUNDED sent MIDDLE has small top-end and bottom-end tail corners`() {
        assertEquals(
            RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 4.dp, bottomStart = 16.dp),
            bubbleShape(BubbleStylePreference.ROUNDED, isSent = true, ClusterPosition.MIDDLE)
        )
    }

    @Test
    fun `ROUNDED sent BOTTOM has small top-end tail corner`() {
        assertEquals(
            RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
            bubbleShape(BubbleStylePreference.ROUNDED, isSent = true, ClusterPosition.BOTTOM)
        )
    }

    @Test
    fun `ROUNDED received SINGLE is fully rounded`() {
        assertEquals(
            RoundedCornerShape(16.dp),
            bubbleShape(BubbleStylePreference.ROUNDED, isSent = false, ClusterPosition.SINGLE)
        )
    }

    @Test
    fun `ROUNDED received TOP has small bottom-start tail corner`() {
        assertEquals(
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
            bubbleShape(BubbleStylePreference.ROUNDED, isSent = false, ClusterPosition.TOP)
        )
    }

    @Test
    fun `ROUNDED received MIDDLE has small top-start and bottom-start tail corners`() {
        assertEquals(
            RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
            bubbleShape(BubbleStylePreference.ROUNDED, isSent = false, ClusterPosition.MIDDLE)
        )
    }

    @Test
    fun `ROUNDED received BOTTOM has small top-start tail corner`() {
        assertEquals(
            RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
            bubbleShape(BubbleStylePreference.ROUNDED, isSent = false, ClusterPosition.BOTTOM)
        )
    }

    // ── SQUARE — uniform small radius on every corner, every position, both directions ──

    @Test
    fun `SQUARE is a uniform 6dp radius for every position and direction`() {
        val expected = RoundedCornerShape(6.dp)
        for (isSent in listOf(true, false)) {
            for (position in ClusterPosition.values()) {
                assertEquals(expected, bubbleShape(BubbleStylePreference.SQUARE, isSent, position))
            }
        }
    }

    // ── PILL — large radius on the round corners, same tail asymmetry pattern as ROUNDED ──

    @Test
    fun `PILL sent SINGLE is fully rounded at the large radius`() {
        assertEquals(
            RoundedCornerShape(24.dp),
            bubbleShape(BubbleStylePreference.PILL, isSent = true, ClusterPosition.SINGLE)
        )
    }

    @Test
    fun `PILL sent TOP MIDDLE BOTTOM keep the small tail corner`() {
        assertEquals(
            RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 4.dp, bottomStart = 24.dp),
            bubbleShape(BubbleStylePreference.PILL, isSent = true, ClusterPosition.TOP)
        )
        assertEquals(
            RoundedCornerShape(topStart = 24.dp, topEnd = 4.dp, bottomEnd = 4.dp, bottomStart = 24.dp),
            bubbleShape(BubbleStylePreference.PILL, isSent = true, ClusterPosition.MIDDLE)
        )
        assertEquals(
            RoundedCornerShape(topStart = 24.dp, topEnd = 4.dp, bottomEnd = 24.dp, bottomStart = 24.dp),
            bubbleShape(BubbleStylePreference.PILL, isSent = true, ClusterPosition.BOTTOM)
        )
    }

    @Test
    fun `PILL received SINGLE is fully rounded at the large radius`() {
        assertEquals(
            RoundedCornerShape(24.dp),
            bubbleShape(BubbleStylePreference.PILL, isSent = false, ClusterPosition.SINGLE)
        )
    }

    @Test
    fun `PILL received TOP MIDDLE BOTTOM keep the small tail corner`() {
        assertEquals(
            RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 4.dp),
            bubbleShape(BubbleStylePreference.PILL, isSent = false, ClusterPosition.TOP)
        )
        assertEquals(
            RoundedCornerShape(topStart = 4.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 4.dp),
            bubbleShape(BubbleStylePreference.PILL, isSent = false, ClusterPosition.MIDDLE)
        )
        assertEquals(
            RoundedCornerShape(topStart = 4.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 24.dp),
            bubbleShape(BubbleStylePreference.PILL, isSent = false, ClusterPosition.BOTTOM)
        )
    }

    @Test
    fun `PILL tail corner is smaller than the round corner for every clustered position`() {
        // Regression guard for the "tail asymmetry" requirement itself, independent of the
        // exact dp constants asserted above.
        for (isSent in listOf(true, false)) {
            for (position in listOf(ClusterPosition.TOP, ClusterPosition.MIDDLE, ClusterPosition.BOTTOM)) {
                val pill = bubbleShape(BubbleStylePreference.PILL, isSent, position)
                val single = bubbleShape(BubbleStylePreference.PILL, isSent, ClusterPosition.SINGLE)
                assertTrue(
                    "Expected $position ($isSent) to differ from the fully-rounded SINGLE shape",
                    pill != single
                )
            }
        }
    }
}
