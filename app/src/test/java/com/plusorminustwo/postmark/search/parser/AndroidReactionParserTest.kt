package com.plusorminustwo.postmark.search.parser

import com.plusorminustwo.postmark.domain.model.Message
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AndroidReactionParser].
 *
 * Android (Google Messages / Samsung) reaction fallbacks arrive as:
 *   [emoji] to "[quoted text]"
 *   [emoji] to "[quoted text]" removed
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
        // "Loved 'some text'" must not be detected as an Android reaction
        assertNull(parser.parse("Loved 'some text'"))
    }

    @Test fun `ascii word before to quote returns null`() {
        // English word followed by to "…" must NOT parse — the first token is ASCII
        assertNull(parser.parse("""Hello to "world""""))
    }

    @Test fun `message without closing quote returns null`() {
        assertNull(parser.parse("""👍 to "unclosed"""))
    }

    // ── findOriginalMessage ────────────────────────────────────────────────

    @Test fun `exact match takes priority`() {
        val candidates = messages("hello", "hello world", "say hello")
        assertEquals("hello", parser.findOriginalMessage("hello", candidates)?.body)
    }

    @Test fun `prefix match used when no exact`() {
        val candidates = messages("hello world", "say hello")
        assertEquals("hello world", parser.findOriginalMessage("hello", candidates)?.body)
    }

    @Test fun `fuzzy containment used as fallback`() {
        val candidates = messages("say hello there", "goodbye")
        assertEquals("say hello there", parser.findOriginalMessage("hello", candidates)?.body)
    }

    @Test fun `no match returns null`() {
        assertNull(parser.findOriginalMessage("hello", messages("goodbye", "see you later")))
    }

    // ── processIncomingMessage ─────────────────────────────────────────────

    @Test fun `processIncomingMessage returns reaction when original found`() {
        val original = message(id = 42, body = "Fine but you're cooking breakfast")
        val incoming = message(id = 99, body = """👍 to "Fine but you're cooking breakfast"""")
        val reaction = parser.processIncomingMessage(incoming, listOf(original, incoming), "+15550001")
        assertNotNull(reaction)
        assertEquals(42L, reaction!!.messageId)
        assertEquals("👍", reaction.emoji)
        assertEquals("+15550001", reaction.senderAddress)
    }

    @Test fun `processIncomingMessage returns null when original not found`() {
        val incoming = message(id = 99, body = """👍 to "unknown message"""")
        assertNull(parser.processIncomingMessage(incoming, listOf(incoming), "+15550001"))
    }

    @Test fun `processIncomingMessage returns null for removal`() {
        val original = message(id = 42, body = "Fine but you're cooking breakfast")
        val incoming = message(id = 99, body = """👍 to "Fine but you're cooking breakfast" removed""")
        assertNull(parser.processIncomingMessage(incoming, listOf(original, incoming), "+15550001"))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun messages(vararg bodies: String) = bodies.mapIndexed { i, body -> message(i.toLong(), body) }

    private fun message(id: Long, body: String) =
        Message(id = id, threadId = 1L, address = "+1", body = body, timestamp = 0L, isSent = false, type = 1)
}
