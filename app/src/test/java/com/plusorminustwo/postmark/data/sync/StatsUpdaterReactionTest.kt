package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for reaction emoji stats in StatsAlgorithms.
 * Pure JVM — no Android runtime, no Room.
 */
class StatsUpdaterReactionTest {

    // ── countReactionEmojis ────────────────────────────────────────────────

    @Test
    fun `empty reaction list returns empty map`() {
        val result = countReactionEmojis(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single reaction returns count of one`() {
        val result = countReactionEmojis(listOf("❤️"))
        assertEquals(1, result["❤️"])
    }

    @Test
    fun `multiple reactions counted correctly`() {
        val result = countReactionEmojis(listOf("❤️", "❤️", "👍", "👍", "👍", "😂"))
        assertEquals(3, result["👍"])
        assertEquals(2, result["❤️"])
        assertEquals(1, result["😂"])
    }

    @Test
    fun `only top 6 reactions returned`() {
        val emojis = listOf("a", "b", "c", "d", "e", "f", "g").flatMapIndexed { i, e ->
            List(7 - i) { e } // a=7, b=6, ..., g=1
        }
        val result = countReactionEmojis(emojis, limit = 6)
        assertEquals(6, result.size)
        assertFalse(result.containsKey("g")) // lowest count excluded
    }

    // ── Thread stats include reaction emoji counts ─────────────────────────

    @Test
    fun `buildThreadStatsData with reactions includes topReactionEmojis`() {
        val messages = listOf(msg(1, isSent = true, t = 1000L))
        val reactions = listOf("❤️", "❤️", "👍")
        val result = buildThreadStatsData(messages, reactions)
        assertEquals(2, result.topReactionEmojis["❤️"])
        assertEquals(1, result.topReactionEmojis["👍"])
    }

    @Test
    fun `buildThreadStatsData without reactions has empty topReactionEmojis`() {
        val messages = listOf(msg(1, isSent = false, t = 1000L))
        val result = buildThreadStatsData(messages)
        assertTrue(result.topReactionEmojis.isEmpty())
    }

    // ── Global stats include reaction emoji counts ─────────────────────────

    @Test
    fun `buildGlobalStatsData with reactions includes topReactionEmojis`() {
        val messages = listOf(msg(1, isSent = true, t = 1000L), msg(2, isSent = false, t = 2000L))
        val reactions = listOf("😂", "😂", "😂", "❤️")
        val result = buildGlobalStatsData(messages, 1, reactions)
        assertEquals(3, result.topReactionEmojis["😂"])
        assertEquals(1, result.topReactionEmojis["❤️"])
    }

    @Test
    fun `buildGlobalStatsData without reactions has empty topReactionEmojis`() {
        val messages = listOf(msg(1, isSent = true, t = 1000L))
        val result = buildGlobalStatsData(messages, 1)
        assertTrue(result.topReactionEmojis.isEmpty())
    }

    // ── Existing message emoji stats unaffected ────────────────────────────

    @Test
    fun `message body emoji stats still work after adding reaction param`() {
        val messages = listOf(msg(1, isSent = true, t = 1000L, body = "Hello 😀 World 😀"))
        val result = buildThreadStatsData(messages)
        assertEquals(2, result.topEmojis["😀"])
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun msg(id: Long, isSent: Boolean, t: Long, body: String = "hello") = MessageEntity(
        id = id, threadId = 1L, address = "+1", body = body, timestamp = t, isSent = isSent
    )
}
