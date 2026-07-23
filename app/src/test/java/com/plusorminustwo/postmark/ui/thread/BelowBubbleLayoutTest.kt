package com.plusorminustwo.postmark.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [belowBubbleLayout] — the pure decision for how reaction pills and the
 * timestamp share the space below a message bubble.
 *
 * Truth table over the two independent inputs (hasReactions × showTimestampRow):
 *  - both        → COMBINED       (pills inner, timestamp outer, one shared level)
 *  - reactions   → PILLS_ONLY
 *  - timestamp   → TIMESTAMP_ONLY (unchanged sibling row below the bubble)
 *  - neither     → NONE
 */
class BelowBubbleLayoutTest {

    @Test
    fun `reactions and timestamp merge onto one level`() {
        assertEquals(
            BelowBubbleLayout.COMBINED,
            belowBubbleLayout(hasReactions = true, showTimestampRow = true)
        )
    }

    @Test
    fun `reactions without a timestamp render pills only`() {
        assertEquals(
            BelowBubbleLayout.PILLS_ONLY,
            belowBubbleLayout(hasReactions = true, showTimestampRow = false)
        )
    }

    @Test
    fun `timestamp without reactions keeps its own row`() {
        assertEquals(
            BelowBubbleLayout.TIMESTAMP_ONLY,
            belowBubbleLayout(hasReactions = false, showTimestampRow = true)
        )
    }

    @Test
    fun `neither reactions nor timestamp renders nothing`() {
        assertEquals(
            BelowBubbleLayout.NONE,
            belowBubbleLayout(hasReactions = false, showTimestampRow = false)
        )
    }
}
