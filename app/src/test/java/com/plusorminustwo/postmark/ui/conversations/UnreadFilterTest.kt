package com.plusorminustwo.postmark.ui.conversations

import com.plusorminustwo.postmark.domain.model.Thread
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure logic behind the conversation-list "unread only" filter:
 * [unreadThreadCount] (the badge count on the filter chip) and [filterThreadsByUnread]
 * (the list shown while the filter is active). Both derive from the same threadId→unread
 * map that feeds the per-row badges, so there is no parallel unread pipeline to drift.
 */
class UnreadFilterTest {

    private fun thread(id: Long, pinned: Boolean = false) =
        Thread(
            id = id,
            displayName = "T$id",
            address = "555000$id",
            lastMessageAt = id,
            isPinned = pinned
        )

    // ── unreadThreadCount ───────────────────────────────────────────────────────

    @Test
    fun `count is zero for an empty map`() {
        assertEquals(0, unreadThreadCount(emptyMap()))
    }

    @Test
    fun `count ignores threads whose unread count is zero`() {
        assertEquals(2, unreadThreadCount(mapOf(1L to 3, 2L to 0, 3L to 1)))
    }

    @Test
    fun `a thread counts once regardless of how many messages are unread`() {
        assertEquals(1, unreadThreadCount(mapOf(7L to 42)))
    }

    // ── filterThreadsByUnread ───────────────────────────────────────────────────

    @Test
    fun `filter off returns the list unchanged`() {
        val list = listOf(thread(1), thread(2), thread(3))
        assertEquals(list, filterThreadsByUnread(list, emptyMap(), showUnreadOnly = false))
    }

    @Test
    fun `filter on keeps only threads with a positive unread count`() {
        val list = listOf(thread(1), thread(2), thread(3))
        val counts = mapOf(1L to 2, 3L to 1) // thread 2 absent (read); thread 3 present
        val result = filterThreadsByUnread(list, counts, showUnreadOnly = true)
        assertEquals(listOf(1L, 3L), result.map { it.id })
    }

    @Test
    fun `a zero entry in the map is treated as read`() {
        val list = listOf(thread(1), thread(2))
        val counts = mapOf(1L to 0, 2L to 5)
        val result = filterThreadsByUnread(list, counts, showUnreadOnly = true)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun `filter preserves pinned-first ordering within the unread subset`() {
        // Caller hands the list already pinned-first; the filter must not reorder.
        val list = listOf(
            thread(10, pinned = true),
            thread(11, pinned = true),
            thread(20),
            thread(21)
        )
        val counts = mapOf(11L to 1, 20L to 4, 21L to 0, 10L to 2)
        val result = filterThreadsByUnread(list, counts, showUnreadOnly = true)
        // 10 and 11 (pinned, unread) stay ahead of 20 (unpinned, unread); 21 (read) drops out.
        assertEquals(listOf(10L, 11L, 20L), result.map { it.id })
    }

    @Test
    fun `filter on with no unread threads yields an empty list`() {
        val list = listOf(thread(1), thread(2))
        assertEquals(emptyList<Thread>(), filterThreadsByUnread(list, emptyMap(), showUnreadOnly = true))
    }
}
