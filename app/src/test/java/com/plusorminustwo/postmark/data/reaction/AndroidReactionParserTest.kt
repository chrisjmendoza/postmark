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
 *   Removed [emoji] from "[quoted text]"  (the real on-device removal shape)
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

    // ── parse — removal PREFIX shape (captured on-device 2026-07-24) ───────

    @Test fun `real device removal body parses with quote intact`() {
        // The exact archived body captured on-device: a "Removed <emoji> from" PREFIX,
        // not the guessed "<emoji> to ... removed" suffix — and the quoted text itself
        // contains embedded double quotes and commas, with the final character of the
        // body being the closing quote.
        val body = "Removed 👍 from \"In the column of \"I don't have kids because of this, " +
            "but here we are,\" Frankie is kind of like one of those kids that tells you that they " +
            "pooped after they pooped while you're trying to potty train them. I was late to work " +
            "this morning because, after giving multiple opportunities to go outside, she decided " +
            "she wanted to poop inside and then barked at me to clean it.\""
        val result = parser.parse(body)
        assertNotNull(result)
        assertEquals("👍", result!!.emoji) // 👍
        assertTrue(result.isRemoval)
        assertEquals(
            "In the column of \"I don't have kids because of this, but here we are,\" Frankie is " +
                "kind of like one of those kids that tells you that they pooped after they pooped " +
                "while you're trying to potty train them. I was late to work this morning because, " +
                "after giving multiple opportunities to go outside, she decided she wanted to poop " +
                "inside and then barked at me to clean it.",
            result.quotedText
        )
    }

    @Test fun `simple removal-prefix message is parsed correctly`() {
        val result = parser.parse("""Removed ❤️ from "hi"""")
        assertNotNull(result)
        assertEquals("❤️", result!!.emoji)
        assertEquals("hi", result.quotedText)
        assertTrue(result.isRemoval)
    }

    @Test fun `removal-prefix with curly quotes is parsed correctly`() {
        val result = parser.parse("Removed 👍 from “hi”")
        assertNotNull(result)
        assertEquals("👍", result!!.emoji)
        assertEquals("hi", result.quotedText)
        assertTrue(result.isRemoval)
    }

    @Test fun `removal-prefix with ascii emoji-position returns null`() {
        assertNull(parser.parse("""Removed x from "hi""""))
    }

    @Test fun `removal-prefix without quotes returns null`() {
        assertNull(parser.parse("Removed 👍 from hi"))
    }

    @Test fun `plain sentence starting with Removed returns null`() {
        assertNull(parser.parse("Removed from the group by admin"))
        assertNull(parser.parse("Removed the old couch from the living room."))
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
