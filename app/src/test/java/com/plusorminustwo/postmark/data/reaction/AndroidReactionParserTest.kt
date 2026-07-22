package com.plusorminustwo.postmark.data.reaction

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AndroidReactionParser] — format recognition only.
 *
 * Android (Google Messages / Samsung) reaction fallbacks arrive as:
 *   [emoji] to "[quoted text]"
 *   [emoji] to "[quoted text]" removed
 *
 * Matching a parsed fallback to its original message is [ReactionFallbackParser]'s
 * job — see ReactionFallbackParserMatchTest.
 */
class AndroidReactionParserTest {

    private lateinit var parser: AndroidReactionParser

    @Before fun setUp() { parser = AndroidReactionParser() }

    // ── parse — standard reactions ────────────────────────────────────────

    @Test fun `thumbs-up reaction is parsed correctly`() {
        val result = parser.parse("""👍 to "Fine but you're cooking breakfast"""")
        assertNotNull(result)
        assertEquals("👍", result!!.emoji)
        assertEquals("Fine but you're cooking breakfast", result.quotedText)
        assertFalse(result.isRemoval)
    }

    @Test fun `heart emoji with variation selector is parsed correctly`() {
        val result = parser.parse("""❤️ to "See you tonight"""")
        assertNotNull(result)
        assertEquals("❤️", result!!.emoji)
        assertEquals("See you tonight", result.quotedText)
        assertFalse(result.isRemoval)
    }

    @Test fun `laughing emoji is parsed correctly`() {
        val result = parser.parse("""😂 to "That was hilarious"""")
        assertNotNull(result)
        assertEquals("😂", result!!.emoji)
        assertEquals("That was hilarious", result.quotedText)
    }

    @Test fun `thumbs-down reaction is parsed correctly`() {
        val result = parser.parse("""👎 to "I can't make it"""")
        assertNotNull(result)
        assertEquals("👎", result!!.emoji)
        assertEquals("I can't make it", result.quotedText)
    }

    // ── parse — quote variants ────────────────────────────────────────────

    @Test fun `curly double quotes are parsed correctly`() {
        val result = parser.parse("👍 to “Fine but you’re cooking breakfast”")
        assertNotNull(result)
        assertEquals("👍", result!!.emoji)
    }

    @Test fun `guillemets are parsed correctly`() {
        val result = parser.parse("👍 to «See you tonight»")
        assertNotNull(result)
        assertEquals("See you tonight", result!!.quotedText)
    }

    // ── parse — removal ────────────────────────────────────────────────────

    @Test fun `removal suffix sets isRemoval true`() {
        val result = parser.parse("""👍 to "Fine but you're cooking breakfast" removed""")
        assertNotNull(result)
        assertTrue(result!!.isRemoval)
        assertEquals("👍", result.emoji)
        assertEquals("Fine but you're cooking breakfast", result.quotedText)
    }

    // ── parse — edge cases ─────────────────────────────────────────────────

    @Test fun `plain message returns null`() {
        assertNull(parser.parse("Hey, how are you?"))
    }

    @Test fun `empty string returns null`() {
        assertNull(parser.parse(""))
    }

    @Test fun `apple-format message returns null`() {
        assertNull(parser.parse("Loved 'some text'"))
    }

    @Test fun `ascii word before to quote returns null`() {
        assertNull(parser.parse("""Hello to "world""""))
    }

    @Test fun `ellipsis-truncated quoted text still parses`() {
        // Long originals (links especially) are ellipsized inside the quotes by the
        // sending platform — parsing must hand the truncated quote through intact so
        // the matcher's truncated-quote strategy can use it.
        val result = parser.parse("❤️ to \"https://music.youtube.com/watch?v=ZKeroWatXDQ&si=F…\"")
        assertNotNull(result)
        assertEquals("❤️", result!!.emoji)
        assertEquals("https://music.youtube.com/watch?v=ZKeroWatXDQ&si=F…", result.quotedText)
    }
}
