package com.plusorminustwo.postmark.domain.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-function tests for [centeredScrollOffsetReverseLayout] — no Android deps. */
class CenteredScrollTest {

    @Test
    fun `centers item within a taller viewport`() {
        // (1000 - 200) / 2 = 400 — half the leftover space lifts the item up from the bottom.
        assertEquals(400, centeredScrollOffsetReverseLayout(viewportHeight = 1000, itemSize = 200))
    }

    @Test
    fun `odd leftover space rounds down via integer division`() {
        // (1001 - 200) / 2 = 400 (801 / 2 truncates).
        assertEquals(400, centeredScrollOffsetReverseLayout(viewportHeight = 1001, itemSize = 200))
        // (1000 - 201) / 2 = 399 (799 / 2 truncates).
        assertEquals(399, centeredScrollOffsetReverseLayout(viewportHeight = 1000, itemSize = 201))
    }

    @Test
    fun `item taller than viewport clamps to zero`() {
        assertEquals(0, centeredScrollOffsetReverseLayout(viewportHeight = 500, itemSize = 900))
    }

    @Test
    fun `item exactly viewport height clamps to zero`() {
        assertEquals(0, centeredScrollOffsetReverseLayout(viewportHeight = 800, itemSize = 800))
    }
}
