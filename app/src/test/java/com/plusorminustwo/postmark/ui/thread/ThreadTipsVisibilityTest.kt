package com.plusorminustwo.postmark.ui.thread

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ThreadViewModel.shouldShowThreadTips] — the visibility rule for the
 * one-time thread gesture-tips card.
 *
 * Rule: show only while the card hasn't been dismissed AND the thread has at least one
 * message (nothing to swipe/long-press/pinch on an empty thread).
 */
class ThreadTipsVisibilityTest {

    @Test
    fun `undismissed thread with messages shows the card`() {
        assertTrue(ThreadViewModel.shouldShowThreadTips(dismissed = false, messageCount = 3))
    }

    @Test
    fun `undismissed but empty thread hides the card`() {
        assertFalse(ThreadViewModel.shouldShowThreadTips(dismissed = false, messageCount = 0))
    }

    @Test
    fun `dismissed hides the card even with messages`() {
        assertFalse(ThreadViewModel.shouldShowThreadTips(dismissed = true, messageCount = 5))
    }

    @Test
    fun `dismissed and empty hides the card`() {
        assertFalse(ThreadViewModel.shouldShowThreadTips(dismissed = true, messageCount = 0))
    }

    @Test
    fun `a single message is enough to show the card`() {
        assertTrue(ThreadViewModel.shouldShowThreadTips(dismissed = false, messageCount = 1))
    }
}
