package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.ui.thread.ThreadViewModel.Companion.SelectionSnapshot
import com.plusorminustwo.postmark.ui.thread.ThreadViewModel.Companion.dismissPicker
import com.plusorminustwo.postmark.ui.thread.ThreadViewModel.Companion.exitSelection
import com.plusorminustwo.postmark.ui.thread.ThreadViewModel.Companion.longPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure long-press / selection reducer
 * ([ThreadViewModel.longPress] / [dismissPicker] / [exitSelection]).
 *
 * These encode the "long-press enters selection directly with an undimmed anchored popup"
 * state contract: a first long-press opens the popup AND selects that message, a dismiss
 * keeps the running selection, leaving selection clears the popup, and a long-press while
 * already selecting toggles like a tap without opening a popup.
 */
class ThreadViewModelSelectionStateTest {

    // ── First long-press (not already selecting) ────────────────────────────────

    @Test
    fun `long-press from idle opens popup and selects exactly that message`() {
        val result = longPress(SelectionSnapshot(), messageId = 42L, bubbleTopY = 80f, bubbleBottomY = 120f)

        assertEquals("popup anchored on the long-pressed message", 42L, result.pickerMessageId)
        assertEquals("bubble bottom Y is retained for anchoring", 120f, result.bubbleY)
        assertEquals("bubble top Y is retained for above-flip", 80f, result.bubbleTopY)
        assertTrue("selection mode is on", result.isSelectionMode)
        assertEquals("selection is exactly the long-pressed message", setOf(42L), result.selection)
        assertEquals("scope resets to MESSAGES", SelectionScope.MESSAGES, result.scope)
    }

    // ── Dismiss (tap outside / back) keeps the selection ────────────────────────

    @Test
    fun `dismiss clears the popup only and keeps selection running`() {
        val open = longPress(SelectionSnapshot(), messageId = 7L, bubbleTopY = 60f, bubbleBottomY = 88f)

        val dismissed = dismissPicker(open)

        assertNull("popup is gone", dismissed.pickerMessageId)
        assertEquals("bubble bottom Y is zeroed", 0f, dismissed.bubbleY)
        assertEquals("bubble top Y is zeroed", 0f, dismissed.bubbleTopY)
        assertTrue("still in selection mode", dismissed.isSelectionMode)
        assertEquals("selection is preserved", setOf(7L), dismissed.selection)
    }

    // ── Exit selection clears the popup too ─────────────────────────────────────

    @Test
    fun `exit selection clears popup and selection`() {
        val cleared = exitSelection()

        assertNull(cleared.pickerMessageId)
        assertEquals(0f, cleared.bubbleY)
        assertFalse(cleared.isSelectionMode)
        assertTrue(cleared.selection.isEmpty())
        assertEquals(SelectionScope.MESSAGES, cleared.scope)
    }

    @Test
    fun `exit selection from a live multi-selection with an open popup clears everything`() {
        val busy = SelectionSnapshot(
            pickerMessageId = 3L,
            bubbleY = 200f,
            isSelectionMode = true,
            selection = setOf(1L, 2L, 3L),
            scope = SelectionScope.ALL
        )

        val cleared = exitSelection()

        // exitSelection ignores prior state by design — it always returns the empty snapshot.
        assertNull(cleared.pickerMessageId)
        assertFalse(cleared.isSelectionMode)
        assertTrue(cleared.selection.isEmpty())
        // Sanity: the input we built really was non-trivial.
        assertTrue(busy.selection.size == 3)
    }

    // ── Second long-press while already selecting = toggle, no popup ─────────────

    @Test
    fun `long-press while selecting adds an unselected message without opening a popup`() {
        val selecting = SelectionSnapshot(
            isSelectionMode = true,
            selection = setOf(1L, 2L)
        )

        val result = longPress(selecting, messageId = 3L, bubbleTopY = 900f, bubbleBottomY = 999f)

        assertNull("no popup while already selecting", result.pickerMessageId)
        assertEquals("bubble Y untouched (no popup)", 0f, result.bubbleY)
        assertEquals("bubble top Y untouched (no popup)", 0f, result.bubbleTopY)
        assertTrue(result.isSelectionMode)
        assertEquals("message 3 added to the running selection", setOf(1L, 2L, 3L), result.selection)
    }

    @Test
    fun `long-press while selecting toggles an already-selected message off`() {
        val selecting = SelectionSnapshot(
            isSelectionMode = true,
            selection = setOf(1L, 2L, 3L)
        )

        val result = longPress(selecting, messageId = 2L, bubbleTopY = 900f, bubbleBottomY = 999f)

        assertNull(result.pickerMessageId)
        assertTrue(result.isSelectionMode)
        assertEquals("message 2 toggled out, others untouched", setOf(1L, 3L), result.selection)
    }

    @Test
    fun `long-press while selecting never clobbers the existing selection`() {
        val selecting = SelectionSnapshot(
            isSelectionMode = true,
            selection = setOf(10L, 20L, 30L)
        )

        val result = longPress(selecting, messageId = 40L, bubbleTopY = 0f, bubbleBottomY = 0f)

        assertTrue("prior selections survive", setOf(10L, 20L, 30L).all { it in result.selection })
    }
}
