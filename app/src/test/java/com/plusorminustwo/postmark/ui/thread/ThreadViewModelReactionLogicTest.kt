package com.plusorminustwo.postmark.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ThreadViewModel.buildQuickEmojiList].
 *
 * This pure function is the core of the "most-used emoji first" feature.
 * It merges the user's top-used emoji list with a defaults list and caps the result.
 *
 * Tests use a fixed defaults list (A–H) for readability rather than real emoji, so
 * that failures are easy to diagnose.
 */
class ThreadViewModelReactionLogicTest {

    private val defaults = listOf("A", "B", "C", "D", "E", "F", "G", "H")

    private fun build(topUsed: List<String>) =
        ThreadViewModel.buildQuickEmojiList(topUsed, defaults, limit = 8)

    // ── empty top list ───────────────────────────────────────────────────────

    @Test
    fun `empty top list returns full defaults`() {
        assertEquals(defaults, build(emptyList()))
    }

    // ── top list shorter than limit ──────────────────────────────────────────

    @Test
    fun `single top emoji is first, remaining defaults fill positions 2-8`() {
        val result = build(listOf("X"))
        assertEquals(listOf("X", "A", "B", "C", "D", "E", "F", "G"), result)
    }

    @Test
    fun `two top emoji are first, remaining defaults fill the rest`() {
        val result = build(listOf("X", "Y"))
        assertEquals(listOf("X", "Y", "A", "B", "C", "D", "E", "F"), result)
    }

    // ── top emoji overlap with defaults ──────────────────────────────────────

    @Test
    fun `top emoji already in defaults are not duplicated`() {
        // "A" is in defaults; should appear once, in its top-used position (first)
        val result = build(listOf("A"))
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H"), result)
        assertEquals("no duplicates", 8, result.distinct().size)
    }

    @Test
    fun `top emoji partially overlapping defaults deduplicates correctly`() {
        // top: X, A, Y → merged+deduped: X A Y B C D E F  (A skipped when iterating defaults)
        val result = build(listOf("X", "A", "Y"))
        assertEquals(listOf("X", "A", "Y", "B", "C", "D", "E", "F"), result)
    }

    @Test
    fun `all top emoji are from defaults — order follows top list`() {
        // top: C B A → defaults fill D E F G H to reach 8
        val result = build(listOf("C", "B", "A"))
        assertEquals(listOf("C", "B", "A", "D", "E", "F", "G", "H"), result)
    }

    // ── cap at limit ─────────────────────────────────────────────────────────

    @Test
    fun `top list exactly fills limit — no defaults added`() {
        val top8 = listOf("P", "Q", "R", "S", "T", "U", "V", "W")
        val result = build(top8)
        assertEquals(top8, result)
    }

    @Test
    fun `top list longer than limit is capped at limit`() {
        val top10 = listOf("P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y")
        val result = build(top10)
        assertEquals(8, result.size)
        assertEquals(top10.take(8), result)
    }

    @Test
    fun `top list longer than limit never adds any defaults`() {
        val top9 = listOf("P", "Q", "R", "S", "T", "U", "V", "W", "X")
        val result = build(top9)
        // None of the defaults (A-H) should appear
        defaults.forEach { default ->
            assert(default !in result) { "Default '$default' should not appear when top list fills the limit" }
        }
    }

    // ── result size invariant ────────────────────────────────────────────────

    @Test
    fun `result size is always exactly limit when defaults are sufficient`() {
        for (n in 0..12) {
            val top = (1..n).map { "X$it" }
            val result = build(top)
            assertEquals(
                "Expected size 8 for topUsed.size=$n",
                8,
                result.size
            )
        }
    }

    @Test
    fun `result size is min(topUsed plus defaults distinct, limit) when defaults overlap with top`() {
        // All 8 defaults are already in top list — merged has only 8 distinct, capped at 8
        val top = defaults
        val result = build(top)
        assertEquals(8, result.size)
        assertEquals(defaults, result) // order from topUsed (which equals defaults here)
    }

    // ── real-emoji smoke test ─────────────────────────────────────────────────

    @Test
    fun `real emoji — most used first with defaults filling gaps`() {
        val realDefaults = ThreadViewModel.DEFAULT_QUICK_EMOJIS
        // User has reacted with ❤️ 12×, 🎉 8×, 😂 4×  (all already in defaults)
        val topUsed = listOf("❤️", "🎉", "😂")
        val result = ThreadViewModel.buildQuickEmojiList(topUsed, realDefaults, 5)
        assertEquals("❤️", result[0])
        assertEquals("🎉", result[1])
        assertEquals("😂", result[2])
        // remaining 2 slots filled by defaults not yet in list
        assertEquals(5, result.size)
        assertEquals(5, result.distinct().size)
    }

    @Test
    fun `real emoji — completely novel emoji come first then defaults fill`() {
        val realDefaults = ThreadViewModel.DEFAULT_QUICK_EMOJIS
        // User used 🦄 (not in defaults) the most
        val topUsed = listOf("🦄")
        val result = ThreadViewModel.buildQuickEmojiList(topUsed, realDefaults, 5)
        assertEquals("🦄", result[0])
        assertEquals(5, result.size)
        // First 4 defaults should fill positions 1–5 in their original order
        val expectedTail = realDefaults.take(4)
        assertEquals(expectedTail, result.drop(1))
    }
}
