package com.plusorminustwo.postmark.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HeatmapSelectionTest {

    private val jul1 = LocalDate.of(2026, 7, 1)
    private val jul2 = LocalDate.of(2026, 7, 2)
    private val jul4 = LocalDate.of(2026, 7, 4)
    private val jul8 = LocalDate.of(2026, 7, 8)

    // ── Single-select mode (taps) ─────────────────────────────────────────

    @Test
    fun `tap selects only the tapped day`() {
        val s = HeatmapSelection().tap(jul1)
        assertEquals(setOf(jul1), s.days)
        assertFalse(s.multiSelect)
    }

    @Test
    fun `tap on a different day replaces the selection`() {
        val s = HeatmapSelection().tap(jul1).tap(jul4)
        assertEquals(setOf(jul4), s.days)
    }

    @Test
    fun `tap on the sole selected day clears the selection`() {
        val s = HeatmapSelection().tap(jul1).tap(jul1)
        assertTrue(s.days.isEmpty())
        assertFalse(s.multiSelect)
    }

    // ── Entering multi-select (long-press) ────────────────────────────────

    @Test
    fun `long-press with no selection enters multi-select with that day`() {
        val s = HeatmapSelection().longPress(jul2)
        assertEquals(setOf(jul2), s.days)
        assertTrue(s.multiSelect)
        assertEquals(jul2, s.anchor)
    }

    @Test
    fun `tap then long-press selects the whole range between them`() {
        val s = HeatmapSelection().tap(jul1).longPress(jul4)
        assertEquals(setOf(jul1, jul2, LocalDate.of(2026, 7, 3), jul4), s.days)
        assertTrue(s.multiSelect)
    }

    @Test
    fun `range selection works backwards too`() {
        val s = HeatmapSelection().tap(jul4).longPress(jul1)
        assertEquals(4, s.days.size)
        assertTrue(jul1 in s.days && jul4 in s.days)
    }

    @Test
    fun `long-press on the already-selected day enters multi-select without a range`() {
        val s = HeatmapSelection().tap(jul1).longPress(jul1)
        assertEquals(setOf(jul1), s.days)
        assertTrue(s.multiSelect)
    }

    // ── Within multi-select ───────────────────────────────────────────────

    @Test
    fun `taps toggle individual days in multi-select`() {
        val s = HeatmapSelection().longPress(jul1).tap(jul4).tap(jul8)
        assertEquals(setOf(jul1, jul4, jul8), s.days)
        val after = s.tap(jul4)
        assertEquals(setOf(jul1, jul8), after.days)
        assertTrue(after.multiSelect)
    }

    @Test
    fun `deselecting the last day exits multi-select`() {
        val s = HeatmapSelection().longPress(jul1).tap(jul1)
        assertTrue(s.days.isEmpty())
        assertFalse(s.multiSelect)
        assertNull(s.anchor)
    }

    @Test
    fun `long-press extends the range from the last long-pressed day`() {
        val s = HeatmapSelection().longPress(jul1).longPress(jul4)
        assertEquals(4, s.days.size)
        assertEquals(jul4, s.anchor)
    }

    @Test
    fun `chained long-presses accumulate ranges`() {
        val s = HeatmapSelection().longPress(jul1).longPress(jul2).longPress(jul4)
        // jul1..jul2 plus jul2..jul4 = jul1..jul4
        assertEquals(4, s.days.size)
    }

    @Test
    fun `range extension adds to existing toggled days`() {
        val s = HeatmapSelection().longPress(jul8).tap(jul1).longPress(jul4)
        // jul8 anchor-ranged to jul4 covers jul4..jul8; the tapped jul1 stays
        assertEquals(setOf(jul1, jul4, LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7), jul8), s.days)
    }

    @Test
    fun `deselecting the anchor clears it so the next long-press only adds that day`() {
        val s = HeatmapSelection().longPress(jul1).tap(jul1).longPress(jul8)
        // tap(jul1) removed the sole day and exited multi-select; the long-press re-enters
        assertEquals(setOf(jul8), s.days)
        assertEquals(jul8, s.anchor)
    }

    @Test
    fun `deselecting the anchor while other days remain nulls the anchor`() {
        val s = HeatmapSelection().longPress(jul1).tap(jul4).tap(jul1)
        assertEquals(setOf(jul4), s.days)
        assertTrue(s.multiSelect)
        assertNull(s.anchor)
        // Next long-press just adds its day (no phantom range from the removed anchor)
        val after = s.longPress(jul8)
        assertEquals(setOf(jul4, jul8), after.days)
    }
}
