package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM unit tests for the statistical algorithms extracted from StatsUpdater.
 * These run on the host JVM (src/test) — no Android runtime, no org.json, no Room DB.
 */
class StatsComputationTest {

    // ── computeLongestStreak ───────────────────────────────────────────────

    @Test
    fun `empty day list returns zero streak`() {
        assertEquals(0, computeLongestStreak(emptyList()))
    }

    @Test
    fun `single day returns streak of one`() {
        assertEquals(1, computeLongestStreak(listOf("2024-01-01")))
    }

    @Test
    fun `five consecutive days returns streak of five`() {
        val days = (1..5).map { "2024-01-0$it" }
        assertEquals(5, computeLongestStreak(days))
    }

    @Test
    fun `gap in sequence returns longest consecutive block`() {
        // Days 1-3, gap, days 5-6 → longest block = 3
        val days = listOf("2024-01-01", "2024-01-02", "2024-01-03", "2024-01-05", "2024-01-06")
        assertEquals(3, computeLongestStreak(days))
    }

    @Test
    fun `alternating days each streak of one`() {
        val days = listOf("2024-01-01", "2024-01-03", "2024-01-05", "2024-01-07")
        assertEquals(1, computeLongestStreak(days))
    }

    @Test
    fun `picks longer block across multiple gaps`() {
        // block 1: 3 days, block 2: 3 days, block 3: 4 days → answer = 4
        val days = listOf(
            "2024-01-01", "2024-01-02", "2024-01-03",
            "2024-01-06", "2024-01-07", "2024-01-08",
            "2024-01-11", "2024-01-12", "2024-01-13", "2024-01-14"
        )
        assertEquals(4, computeLongestStreak(days))
    }

    @Test
    fun `two consecutive days returns streak of two`() {
        val days = listOf("2024-03-10", "2024-03-11")
        assertEquals(2, computeLongestStreak(days))
    }

    @Test
    fun `month boundary is handled correctly`() {
        // Jan 31 → Feb 1 → Feb 2 = 3 consecutive
        val days = listOf("2024-01-31", "2024-02-01", "2024-02-02")
        assertEquals(3, computeLongestStreak(days))
    }

    // ── extractEmojis ──────────────────────────────────────────────────────

    @Test
    fun `plain text has no emojis`() {
        assertTrue(extractEmojis("Hello world!").isEmpty())
    }

    @Test
    fun `empty string has no emojis`() {
        assertTrue(extractEmojis("").isEmpty())
    }

    @Test
    fun `single supplementary-plane emoji is extracted`() {
        // 😂 = U+1F602, in range 0x1F000..0x1FAFF
        assertEquals(listOf("😂"), extractEmojis("😂"))
    }

    @Test
    fun `emoji surrounded by text is extracted`() {
        assertEquals(listOf("😂"), extractEmojis("haha 😂 so funny"))
    }

    @Test
    fun `multiple different emojis all extracted`() {
        // 😂 U+1F602 and 🎉 U+1F389 are both in 0x1F300..0x1FAFF
        val result = extractEmojis("party 🎉 and laugh 😂")
        assertEquals(2, result.size)
        assertTrue(result.contains("😂"))
        assertTrue(result.contains("🎉"))
    }

    @Test
    fun `repeated emoji counted multiple times`() {
        val result = extractEmojis("😂😂😂")
        assertEquals(3, result.size)
        assertTrue(result.all { it == "😂" })
    }

    @Test
    fun `BMP emoji in misc-symbols range extracted`() {
        // ☀ = U+2600, in range 0x2600..0x27BF
        val result = extractEmojis("sunny day ☀")
        assertTrue(result.isNotEmpty())
        assertEquals("☀", result[0])
    }

    @Test
    fun `ASCII and letters are not extracted`() {
        assertTrue(extractEmojis("abc ABC 123 !?.,").isEmpty())
    }

    // ── isEmojiCodePoint ──────────────────────────────────────────────────

    @Test
    fun `supplementary emoji code point returns true`() {
        assertTrue(isEmojiCodePoint(0x1F602)) // 😂
        assertTrue(isEmojiCodePoint(0x1F389)) // 🎉
        assertTrue(isEmojiCodePoint(0x1F44D)) // 👍
    }

    @Test
    fun `BMP symbol code point returns true`() {
        assertTrue(isEmojiCodePoint(0x2600)) // ☀
        assertTrue(isEmojiCodePoint(0x2764)) // ❤
    }

    @Test
    fun `ASCII code point returns false`() {
        assertFalse(isEmojiCodePoint('A'.code))
        assertFalse(isEmojiCodePoint('1'.code))
        assertFalse(isEmojiCodePoint('!'.code))
    }

    // ── computeAvgResponseTime ─────────────────────────────────────────────

    @Test
    fun `empty list returns zero`() {
        assertEquals(0L, computeAvgResponseTime(emptyList()))
    }

    @Test
    fun `single message returns zero (no pairs)`() {
        assertEquals(0L, computeAvgResponseTime(listOf(msg(1, isSent = true, t = 1000L))))
    }

    @Test
    fun `all same sender returns zero`() {
        val msgs = listOf(
            msg(1, isSent = true, t = 0L),
            msg(2, isSent = true, t = 1000L),
            msg(3, isSent = true, t = 2000L)
        )
        assertEquals(0L, computeAvgResponseTime(msgs))
    }

    @Test
    fun `one response pair returns exact delta`() {
        val msgs = listOf(
            msg(1, isSent = true, t = 0L),
            msg(2, isSent = false, t = 3_600_000L) // 1 hour later
        )
        assertEquals(3_600_000L, computeAvgResponseTime(msgs))
    }

    @Test
    fun `two alternating response pairs returns average`() {
        // pair 1: 0 → 1000ms = 1000, pair 2: 2000 → 5000 = 3000 → avg = 2000
        val msgs = listOf(
            msg(1, isSent = true, t = 0L),
            msg(2, isSent = false, t = 1_000L),
            msg(3, isSent = false, t = 2_000L), // same sender, skipped
            msg(4, isSent = true, t = 5_000L)
        )
        assertEquals(2_000L, computeAvgResponseTime(msgs))
    }

    @Test
    fun `response counted from previous differing sender`() {
        // sent 0 → recv 1000 (pair, delta 1000)
        // recv 1000 → recv 2000 (same, no pair)
        // recv 2000 → sent 5000 (pair, delta 3000)
        // avg = (1000 + 3000) / 2 = 2000
        val msgs = listOf(
            msg(1, isSent = true, t = 0L),
            msg(2, isSent = false, t = 1_000L),
            msg(3, isSent = false, t = 2_000L),
            msg(4, isSent = true, t = 5_000L)
        )
        assertEquals(2_000L, computeAvgResponseTime(msgs))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun msg(id: Long, isSent: Boolean, t: Long) = MessageEntity(
        id = id, threadId = 1L, address = "+1", body = "x",
        timestamp = t, isSent = isSent, type = 1
    )
}
