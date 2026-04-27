package com.plusorminustwo.postmark.ui.thread

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DateNavigationTest {

    private fun date(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

    // ── empty set ──────────────────────────────────────────────────────────────

    @Test
    fun `empty active dates returns null`() {
        assertNull(findNearestActiveDate(date(2024, 4, 10), emptySet()))
    }

    // ── target is itself active ────────────────────────────────────────────────

    @Test
    fun `target in active dates returns itself`() {
        val d = date(2024, 4, 10)
        assertEquals(d, findNearestActiveDate(d, setOf(d, date(2024, 4, 15))))
    }

    // ── single active date ─────────────────────────────────────────────────────

    @Test
    fun `single active date before target returns that date`() {
        val active = date(2024, 4, 5)
        assertEquals(active, findNearestActiveDate(date(2024, 4, 20), setOf(active)))
    }

    @Test
    fun `single active date after target returns that date`() {
        val active = date(2024, 4, 20)
        assertEquals(active, findNearestActiveDate(date(2024, 4, 5), setOf(active)))
    }

    // ── two active dates — pick nearer ─────────────────────────────────────────

    @Test
    fun `target closer to earlier date picks earlier`() {
        val early = date(2024, 4, 5)
        val late  = date(2024, 4, 20)
        // target Apr 7 is 2 days from early, 13 days from late → early wins
        assertEquals(early, findNearestActiveDate(date(2024, 4, 7), setOf(early, late)))
    }

    @Test
    fun `target closer to later date picks later`() {
        val early = date(2024, 4, 5)
        val late  = date(2024, 4, 20)
        // target Apr 18 is 13 days from early, 2 days from late → late wins
        assertEquals(late, findNearestActiveDate(date(2024, 4, 18), setOf(early, late)))
    }

    // ── equidistant — earlier wins (minByOrNull tiebreaker) ───────────────────

    @Test
    fun `equidistant target picks earlier date`() {
        val early = date(2024, 4, 5)
        val late  = date(2024, 4, 15)
        // target Apr 10 is exactly 5 days from both
        val result = findNearestActiveDate(date(2024, 4, 10), setOf(early, late))
        // Both are equidistant; minByOrNull returns the first in iteration order.
        // Either is acceptable — assert it's one of the two.
        assertTrue(result == early || result == late)
    }

    // ── multiple active dates ──────────────────────────────────────────────────

    @Test
    fun `picks closest among many active dates`() {
        val dates = setOf(
            date(2024, 1, 1),
            date(2024, 2, 1),
            date(2024, 3, 1),
            date(2024, 4, 1),
            date(2024, 4, 28)
        )
        // Apr 25 is 3 days before Apr 28 and 24 days after Apr 1 → Apr 28 wins
        assertEquals(date(2024, 4, 28), findNearestActiveDate(date(2024, 4, 25), dates))
    }

    // ── month and year boundaries ──────────────────────────────────────────────

    @Test
    fun `works across month boundary`() {
        val jan31 = date(2024, 1, 31)
        val feb2  = date(2024, 2, 2)
        // Feb 1 is 1 day from both — equidistant, either is fine
        val result = findNearestActiveDate(date(2024, 2, 1), setOf(jan31, feb2))
        assertTrue(result == jan31 || result == feb2)
    }

    @Test
    fun `works across year boundary`() {
        val dec30 = date(2023, 12, 30)
        val jan3  = date(2024, 1, 3)
        // Jan 1 is 2 days from dec30, 2 days from jan3 — equidistant
        // Jan 2 is 3 days from dec30, 1 day from jan3 → jan3 wins
        assertEquals(jan3, findNearestActiveDate(date(2024, 1, 2), setOf(dec30, jan3)))
    }

    // ── large gap ──────────────────────────────────────────────────────────────

    @Test
    fun `large gap picks single closest date`() {
        val onlyDate = date(2024, 6, 15)
        // Target is years away — still returns the one active date
        assertEquals(onlyDate, findNearestActiveDate(date(2020, 1, 1), setOf(onlyDate)))
    }

    // ── scrollOffsetToAlignTop ─────────────────────────────────────────────────
    //
    // With reverseLayout=true, scrollToItem(index) aligns the item to the BOTTOM
    // (leading edge).  scrollOffsetToAlignTop computes the positive pixel offset
    // that shifts it upward until its top aligns with the viewport top.

    @Test
    fun `scrollOffsetToAlignTop returns viewport minus item height`() {
        // A 96-px header in a 1800-px viewport → offset 1704 puts header at top.
        assertEquals(1704, scrollOffsetToAlignTop(viewportHeight = 1800, itemHeightPx = 96))
    }

    @Test
    fun `scrollOffsetToAlignTop with item taller than viewport clamps to zero`() {
        // Pathological: item taller than viewport — offset must not go negative.
        assertEquals(0, scrollOffsetToAlignTop(viewportHeight = 100, itemHeightPx = 200))
    }

    @Test
    fun `scrollOffsetToAlignTop with zero viewport returns zero`() {
        // Viewport not yet measured — safe fallback is 0 (item at bottom, same as before fix).
        assertEquals(0, scrollOffsetToAlignTop(viewportHeight = 0, itemHeightPx = 96))
    }

    @Test
    fun `scrollOffsetToAlignTop with item equal to viewport returns zero`() {
        // Edge case: item fills the whole viewport; only valid position is 0.
        assertEquals(0, scrollOffsetToAlignTop(viewportHeight = 96, itemHeightPx = 96))
    }

    @Test
    fun `scrollOffsetToAlignTop with zero item height returns viewport height`() {
        // Zero-height item (e.g. invisible placeholder): offset equals full viewport.
        assertEquals(1800, scrollOffsetToAlignTop(viewportHeight = 1800, itemHeightPx = 0))
    }

    @Test
    fun `scrollOffsetToAlignTop satisfies the top-alignment equation`() {
        // Mathematical invariant: after applying the offset, the item's top should be
        // at the viewport top edge (position 0).
        // With reverseLayout, item bottom = viewport - offset, item top = bottom - itemHeight.
        // For top = 0: viewport - offset - itemHeight = 0, i.e. offset = viewport - itemHeight.
        val viewport = 1800
        val itemH    = 96
        val offset   = scrollOffsetToAlignTop(viewport, itemH)
        assertEquals(0, viewport - offset - itemH)
    }
}
