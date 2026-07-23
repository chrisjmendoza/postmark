package com.plusorminustwo.postmark.domain.selection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [resolveSwipeAction] — the swipe-left-to-delete /
 * swipe-right-to-toggle-read decision behind ConversationsScreen's swipe gestures.
 */
class SwipeActionTest {

    @Test
    fun `EndToStart always deletes regardless of read state`() {
        assertEquals(SwipeAction.Delete, resolveSwipeAction(SwipeDirection.EndToStart, isRead = true))
        assertEquals(SwipeAction.Delete, resolveSwipeAction(SwipeDirection.EndToStart, isRead = false))
    }

    @Test
    fun `StartToEnd marks an unread thread read`() {
        assertEquals(SwipeAction.MarkRead, resolveSwipeAction(SwipeDirection.StartToEnd, isRead = false))
    }

    @Test
    fun `StartToEnd marks a read thread unread`() {
        assertEquals(SwipeAction.MarkUnread, resolveSwipeAction(SwipeDirection.StartToEnd, isRead = true))
    }
}
