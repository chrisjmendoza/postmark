package com.plusorminustwo.postmark.domain.search

import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-transform tests for [groupResultsByContact] — no Android deps. */
class SearchGroupingTest {

    private fun msg(id: Long, threadId: Long, timestamp: Long, address: String = "+1") =
        Message(
            id = id,
            threadId = threadId,
            address = address,
            body = "body $id",
            timestamp = timestamp,
            isSent = false,
            type = 1
        )

    private fun thread(id: Long, name: String, address: String = "+$id") =
        Thread(id = id, displayName = name, address = address, lastMessageAt = 0L)

    @Test
    fun `empty results produce no groups`() {
        assertEquals(emptyList<SearchResultGroup>(), groupResultsByContact(emptyList(), emptyList()))
    }

    @Test
    fun `groups are ordered A to Z by display name`() {
        val threads = listOf(thread(1, "Charlie"), thread(2, "Alice"), thread(3, "Bob"))
        val results = listOf(msg(10, 1, 100), msg(11, 2, 100), msg(12, 3, 100))

        val groups = groupResultsByContact(results, threads)

        assertEquals(listOf("Alice", "Bob", "Charlie"), groups.map { it.displayName })
    }

    @Test
    fun `A to Z ordering is case insensitive`() {
        // "alice" (lowercase) must still sort before "Bob", not after it (uppercase < lowercase in ASCII).
        val threads = listOf(thread(1, "Bob"), thread(2, "alice"))
        val results = listOf(msg(10, 1, 100), msg(11, 2, 100))

        val groups = groupResultsByContact(results, threads)

        assertEquals(listOf("alice", "Bob"), groups.map { it.displayName })
    }

    @Test
    fun `messages within a group are newest first`() {
        val threads = listOf(thread(1, "Alice"))
        val results = listOf(msg(10, 1, 100), msg(11, 1, 300), msg(12, 1, 200))

        val groups = groupResultsByContact(results, threads)

        assertEquals(1, groups.size)
        assertEquals(listOf(11L, 12L, 10L), groups.first().messages.map { it.id })
    }

    @Test
    fun `oldest first orders messages ascending within a group`() {
        val threads = listOf(thread(1, "Alice"))
        val results = listOf(msg(10, 1, 100), msg(11, 1, 300), msg(12, 1, 200))

        val groups = groupResultsByContact(results, threads, oldestFirst = true)

        assertEquals(1, groups.size)
        assertEquals(listOf(10L, 12L, 11L), groups.first().messages.map { it.id })
    }

    @Test
    fun `group A to Z order is unaffected by sort direction`() {
        val threads = listOf(thread(1, "Charlie"), thread(2, "Alice"), thread(3, "Bob"))
        val results = listOf(msg(10, 1, 100), msg(11, 2, 100), msg(12, 3, 100))

        val newest = groupResultsByContact(results, threads, oldestFirst = false)
        val oldest = groupResultsByContact(results, threads, oldestFirst = true)

        val expected = listOf("Alice", "Bob", "Charlie")
        assertEquals(expected, newest.map { it.displayName })
        assertEquals(expected, oldest.map { it.displayName })
    }

    @Test
    fun `two threads with the same display name stay separate groups`() {
        val threads = listOf(thread(1, "Mom", "+111"), thread(2, "Mom", "+222"))
        val results = listOf(msg(10, 1, 100), msg(11, 2, 100))

        val groups = groupResultsByContact(results, threads)

        assertEquals(2, groups.size)
        assertEquals(setOf(1L, 2L), groups.map { it.threadId }.toSet())
        assertTrue(groups.all { it.displayName == "Mom" })
    }

    @Test
    fun `display name falls back to address when no thread matches`() {
        val results = listOf(msg(10, 99, 100, address = "+15551234"))

        val groups = groupResultsByContact(results, threads = emptyList())

        assertEquals(1, groups.size)
        assertEquals("+15551234", groups.first().displayName)
        assertEquals(null, groups.first().thread)
    }

    // --- toggleGroupCollapsed / collapseAll / expandAll / anyGroupExpanded ---

    @Test
    fun `toggleGroupCollapsed collapses an expanded group`() {
        val result = toggleGroupCollapsed(emptySet(), key = 1L)
        assertEquals(setOf(1L), result)
    }

    @Test
    fun `toggleGroupCollapsed expands a collapsed group`() {
        val result = toggleGroupCollapsed(setOf(1L, 2L), key = 1L)
        assertEquals(setOf(2L), result)
    }

    @Test
    fun `toggleGroupCollapsed only touches the given key`() {
        val result = toggleGroupCollapsed(setOf(2L), key = 1L)
        assertEquals(setOf(1L, 2L), result)
    }

    @Test
    fun `collapseAll with empty groups produces an empty set`() {
        assertEquals(emptySet<Long>(), collapseAll(emptyList()))
    }

    @Test
    fun `collapseAll collapses every given key`() {
        assertEquals(setOf(1L, 2L, 3L), collapseAll(listOf(1L, 2L, 3L)))
    }

    @Test
    fun `expandAll always returns the empty set`() {
        assertEquals(emptySet<Long>(), expandAll())
    }

    @Test
    fun `anyGroupExpanded is false when every group is collapsed`() {
        assertEquals(false, anyGroupExpanded(setOf(1L, 2L), groupKeys = listOf(1L, 2L)))
    }

    @Test
    fun `anyGroupExpanded is true when at least one group is expanded`() {
        assertEquals(true, anyGroupExpanded(setOf(1L), groupKeys = listOf(1L, 2L)))
    }

    @Test
    fun `anyGroupExpanded is false for an empty group key set`() {
        assertEquals(false, anyGroupExpanded(emptySet(), groupKeys = emptyList()))
    }
}
