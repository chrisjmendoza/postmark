package com.plusorminustwo.postmark.domain.selection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [partitionForBlock] and [blockResultMessage] — the pure decision logic
 * behind the conversation-list bulk "Block" action. Group threads have no single number to
 * block, so they're partitioned out and reported as "skipped" rather than blocked.
 */
class BlockSelectionTest {

    @Test
    fun `no groups blocks every address`() {
        val result = partitionForBlock(
            listOf(
                BlockCandidate("555-0100", isGroup = false),
                BlockCandidate("555-0101", isGroup = false)
            )
        )
        assertEquals(listOf("555-0100", "555-0101"), result.addressesToBlock)
        assertEquals(0, result.skippedGroupCount)
    }

    @Test
    fun `all groups blocks nothing`() {
        val result = partitionForBlock(
            listOf(
                BlockCandidate("group-1", isGroup = true),
                BlockCandidate("group-2", isGroup = true)
            )
        )
        assertEquals(emptyList<String>(), result.addressesToBlock)
        assertEquals(2, result.skippedGroupCount)
    }

    @Test
    fun `mixed selection blocks singles and skips groups`() {
        val result = partitionForBlock(
            listOf(
                BlockCandidate("555-0100", isGroup = false),
                BlockCandidate("group-1", isGroup = true),
                BlockCandidate("555-0101", isGroup = false)
            )
        )
        assertEquals(listOf("555-0100", "555-0101"), result.addressesToBlock)
        assertEquals(1, result.skippedGroupCount)
    }

    @Test
    fun `empty selection blocks nothing and skips nothing`() {
        val result = partitionForBlock(emptyList())
        assertEquals(emptyList<String>(), result.addressesToBlock)
        assertEquals(0, result.skippedGroupCount)
    }

    @Test
    fun `message with no skips omits the skip clause`() {
        assertEquals("Blocked 2", blockResultMessage(blockedCount = 2, skippedGroupCount = 0))
    }

    @Test
    fun `message with one skip is singular`() {
        assertEquals(
            "Blocked 2 · skipped 1 group chat",
            blockResultMessage(blockedCount = 2, skippedGroupCount = 1)
        )
    }

    @Test
    fun `message with multiple skips is plural`() {
        assertEquals(
            "Blocked 2 · skipped 3 group chats",
            blockResultMessage(blockedCount = 2, skippedGroupCount = 3)
        )
    }

    @Test
    fun `message with zero blocked but skips still reports the skip count`() {
        assertEquals(
            "Blocked 0 · skipped 2 group chats",
            blockResultMessage(blockedCount = 0, skippedGroupCount = 2)
        )
    }
}
