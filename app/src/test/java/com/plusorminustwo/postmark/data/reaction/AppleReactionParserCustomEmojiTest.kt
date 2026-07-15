package com.plusorminustwo.postmark.data.reaction

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests the iOS 17+ custom-emoji tapback branch of the *real* [AppleReactionParser]
 * (constructed via its internal patterns-provider constructor, so no Android Context /
 * asset loading is needed).
 *
 * The six named tapbacks (Loved/Liked/…) map a verb to an emoji. Any other emoji is
 * carried literally in the fallback text as `Reacted 😎 to "…"` — Android↔iPhone
 * threads produce these whenever the reactor picks an emoji outside the classic set.
 * Before this branch existed the whole string parsed as an unknown "verb" and rendered
 * as a normal message bubble.
 */
class AppleReactionParserCustomEmojiTest {

    private lateinit var parser: AppleReactionParser

    @Before fun setUp() {
        parser = AppleReactionParser {
            listOf(
                AppleReactionParser.ReactionPattern("❤️", listOf("Loved"), listOf("Removed a heart from")),
                AppleReactionParser.ReactionPattern("👍", listOf("Liked"), listOf("Removed a like from"))
            )
        }
    }

    // ── custom-emoji add ──────────────────────────────────────────────────

    @Test fun `sunglasses custom tapback is parsed`() {
        val result = parser.parse("""Reacted 😎 to "+1 point"""")
        assertNotNull(result)
        assertEquals("😎", result!!.emoji)
        assertEquals("+1 point", result.quotedText)
        assertFalse(result.isRemoval)
    }

    @Test fun `custom tapback preserves multi-line quoted text`() {
        val result = parser.parse("Reacted 🎉 to \"line one\nline two\"")
        assertNotNull(result)
        assertEquals("🎉", result!!.emoji)
        assertEquals("line one\nline two", result.quotedText)
    }

    @Test fun `custom tapback quoted with smart quotes`() {
        val result = parser.parse("Reacted 🔥 to “nice work”")
        assertEquals("🔥", result!!.emoji)
        assertEquals("nice work", result.quotedText)
    }

    // ── custom-emoji removal ──────────────────────────────────────────────

    @Test fun `custom tapback removal sets isRemoval`() {
        val result = parser.parse("""Removed a 😎 from "+1 point"""")
        assertNotNull(result)
        assertEquals("😎", result!!.emoji)
        assertTrue(result.isRemoval)
    }

    @Test fun `custom tapback removal with reaction word`() {
        val result = parser.parse("""Removed a 🔥 reaction from "nice work"""")
        assertNotNull(result)
        assertEquals("🔥", result!!.emoji)
        assertTrue(result.isRemoval)
    }

    // ── named tapbacks still win over the custom branch ───────────────────

    @Test fun `named loved verb still maps to heart`() {
        val result = parser.parse("""Loved "See you tonight"""")
        assertEquals("❤️", result!!.emoji)
        assertFalse(result.isRemoval)
    }

    // ── the non-ASCII guard rejects real sentences ────────────────────────

    @Test fun `reacted with a real word is not a reaction`() {
        // "Reacted quickly to …" is an ordinary sentence, not a tapback.
        assertNull(parser.parse("""Reacted quickly to "the news""""))
    }

    @Test fun `removed a real noun is not a reaction`() {
        assertNull(parser.parse("""Removed a book from "the shelf""""))
    }

    @Test fun `plain message is not a reaction`() {
        assertNull(parser.parse("""Reacted to your story"""))
    }
}
