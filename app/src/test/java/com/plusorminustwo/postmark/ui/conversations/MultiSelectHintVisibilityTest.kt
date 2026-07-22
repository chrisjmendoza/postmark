package com.plusorminustwo.postmark.ui.conversations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [shouldShowMultiSelectHint] — the visibility rule for the one-time
 * long-press-to-multi-select hint row on the conversation list.
 *
 * Rule: show only while the hint hasn't been dismissed, the list has conversations, and
 * selection mode isn't already active.
 */
class MultiSelectHintVisibilityTest {

    @Test
    fun `undismissed non-empty list not selecting shows the hint`() {
        assertTrue(shouldShowMultiSelectHint(dismissed = false, threadCount = 4, selectionActive = false))
    }

    @Test
    fun `dismissed hides the hint`() {
        assertFalse(shouldShowMultiSelectHint(dismissed = true, threadCount = 4, selectionActive = false))
    }

    @Test
    fun `empty list hides the hint`() {
        assertFalse(shouldShowMultiSelectHint(dismissed = false, threadCount = 0, selectionActive = false))
    }

    @Test
    fun `active selection hides the hint`() {
        assertFalse(shouldShowMultiSelectHint(dismissed = false, threadCount = 4, selectionActive = true))
    }

    @Test
    fun `a single conversation is enough to show the hint`() {
        assertTrue(shouldShowMultiSelectHint(dismissed = false, threadCount = 1, selectionActive = false))
    }
}
