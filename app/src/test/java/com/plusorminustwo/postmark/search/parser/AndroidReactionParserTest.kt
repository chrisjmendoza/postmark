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

    // ── parse — quote variants ────────────────────────────────────────────

    @Test fun `curly double quotes are parsed correctly`() {
        val result = parser.parse("\uD83D\uDC4D to \u201CFine but you\u2019re cooking breakfast\u201D")
        assertNotNull(result)
        assertEquals("\uD83D\uDC4D", result!!.emoji)
    }

    @Test fun `guillemets are parsed correctly`() {
        val result = parser.parse("\uD83D\uDC4D to \u00ABSee you tonight\u00BB")
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

    // ── findOriginalMessage ────────────────────────────────────────────────

    @Test fun `exact match takes priority`() {
        val candidates = messages("hello", "hello world", "say hello")
        assertEquals("hello", parser.findOriginalMessage("hello", candidates)?.body)
    }

    @Test fun `prefix match used when no exact`() {
        val candidates = messages("hello world", "say hello")
        assertEquals("hello world", parser.findOriginalMessage("hello", candidates)?.body)
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

    @Test fun `processIncomingMessage returns reaction for removal when original found`() {
        // Removal reactions return a non-null Reaction so the caller can use messageId
        // to call deleteReaction. The caller checks ParsedReaction.isRemoval to decide action.
        val original = message(id = 42, body = "Fine but you're cooking breakfast")
        val incoming = message(id = 99, body = """👍 to "Fine but you're cooking breakfast" removed""")
        val reaction = parser.processIncomingMessage(incoming, listOf(original, incoming), "+15550001")
        assertNotNull(reaction)
        assertEquals(42L, reaction!!.messageId)
        assertEquals("👍", reaction.emoji)
    }

    // ── findOriginalMessage — newest-to-oldest search order ───────────────

    @Test fun `findOriginalMessage returns newest match when duplicates exist`() {
        // Two messages with the same body; newest should win.
        val older  = messageAt(id = 1, body = "Let's go!", timestamp = 1000L)
        val newer  = messageAt(id = 2, body = "Let's go!", timestamp = 2000L)
        val result = parser.findOriginalMessage("Let's go!", listOf(older, newer))
        assertEquals(2L, result?.id)
    }

    @Test fun `findOriginalMessage searches newest first so early match wins over older`() {
        val old    = messageAt(id = 1, body = "See you tomorrow", timestamp = 1000L)
        val recent = messageAt(id = 2, body = "See you tomorrow", timestamp = 9000L)
        // Candidates given in oldest-first order — parser must re-sort internally.
        val result = parser.findOriginalMessage("See you tomorrow", listOf(old, recent))
        assertEquals(2L, result?.id)
    }

    // ── findOriginalMessage — 100 message limit ───────────────────────────

    @Test fun `findOriginalMessage returns null when match is beyond 100 messages`() {
        // 101 candidates, exact match is the very oldest (position 101 newest-to-oldest).
        val oldest = messageAt(id = 0, body = "target message", timestamp = 0L)
        val others = (1..100).map { i -> messageAt(id = i.toLong(), body = "msg $i", timestamp = i * 1000L) }
        assertNull(parser.findOriginalMessage("target message", listOf(oldest) + others))
    }

    @Test fun `findOriginalMessage returns match when it is exactly at position 100`() {
        // Match is the 100th newest — should be found.
        val target = messageAt(id = 1, body = "target message", timestamp = 1000L)
        val others = (2..100).map { i -> messageAt(id = i.toLong(), body = "msg $i", timestamp = i * 1000L) }
        assertNotNull(parser.findOriginalMessage("target message", listOf(target) + others))
    }

    // ── findOriginalMessage — Unicode normalization ───────────────────────

    @Test fun `normalized apostrophe U+2019 matches straight apostrophe in query`() {
        // Candidate body uses Apple smart apostrophe; reaction quoted it with straight apostrophe.
        val candidate = messageAt(id = 1, body = "I\u2019m built for it", timestamp = 1000L)
        val result = parser.findOriginalMessage("I'm built for it", listOf(candidate))
        assertNotNull(result)
        assertEquals(1L, result?.id)
    }

    @Test fun `normalized apostrophe matches when candidate uses straight and query uses smart`() {
        val candidate = messageAt(id = 1, body = "I'm built for it", timestamp = 1000L)
        val result = parser.findOriginalMessage("I\u2019m built for it", listOf(candidate))
        assertNotNull(result)
    }

    @Test fun `normalized smart double quotes match straight quotes`() {
        val candidate = messageAt(id = 1, body = "He said \"hello\"", timestamp = 1000L)
        val result = parser.findOriginalMessage("He said \u201Chello\u201D", listOf(candidate))
        assertNotNull(result)
    }

    @Test fun `normalized ellipsis matches three dots`() {
        val candidate = messageAt(id = 1, body = "Wait...", timestamp = 1000L)
        val result = parser.findOriginalMessage("Wait\u2026", listOf(candidate))
        assertNotNull(result)
    }

    @Test fun `normalized em dash matches hyphen`() {
        val candidate = messageAt(id = 1, body = "rock - paper", timestamp = 1000L)
        val result = parser.findOriginalMessage("rock \u2014 paper", listOf(candidate))
        assertNotNull(result)
    }

    // ── findOriginalMessage — no contains match ───────────────────────────

    @Test fun `reaction body itself is not matched via contains`() {
        // The reaction message body literally contains the quoted text but should NOT
        // match because we removed the fuzzy contains strategy.
        val reactionMsg = messageAt(id = 99, body = """👍 to "Fine but you're cooking breakfast"""", timestamp = 5000L)
        val result = parser.findOriginalMessage(
            "Fine but you're cooking breakfast",
            listOf(reactionMsg) // reaction message is the only candidate — should not self-match
        )
        // Contains match is gone; only exact/normalized/prefix are tried.
        // The reaction body does not EQUAL the quoted text, so result must be null.
        assertNull(result)
    }

    @Test fun `processIncomingMessage does not produce dangling reaction pointing at itself`() {
        // This was the core bug: when only the reaction message was in candidates,
        // the contains fallback matched the reaction body and pointed the reaction at
        // the message being deleted, producing a dangling reaction entity.
        val incoming = messageAt(id = 99, body = """❤️ to "Yeah, my body really likes bike riding"""", timestamp = 5000L)
        val reaction = parser.processIncomingMessage(incoming, listOf(incoming), "+15550001")
        // processIncomingMessage filters incoming.id from candidates, so nothing matches.
        assertNull(reaction)
    }

    // ── normalize — internal helper ───────────────────────────────────────

    @Test fun `normalize replaces smart single quotes`() {
        assertEquals("I'm here", parser.normalize("I\u2019m here"))
        assertEquals("it's done", parser.normalize("it\u2018s done"))
    }

    @Test fun `normalize replaces smart double quotes`() {
        assertEquals("\"hello\"", parser.normalize("\u201Chello\u201D"))
    }

    @Test fun `normalize replaces ellipsis`() {
        assertEquals("wait...", parser.normalize("wait\u2026"))
    }

    @Test fun `normalize replaces em and en dash`() {
        assertEquals("a-b", parser.normalize("a\u2014b"))
        assertEquals("a-b", parser.normalize("a\u2013b"))
    }

    @Test fun `normalize is idempotent on plain ASCII`() {
        val plain = "Hello, how are you? I'm fine."
        assertEquals(plain, parser.normalize(plain))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun messages(vararg bodies: String) = bodies.mapIndexed { i, body -> message(i.toLong(), body) }

    private fun message(id: Long, body: String) =
        Message(id = id, threadId = 1L, address = "+1", body = body, timestamp = 0L, isSent = false, type = 1)

    private fun messageAt(id: Long, body: String, timestamp: Long) =
        Message(id = id, threadId = 1L, address = "+1", body = body, timestamp = timestamp, isSent = false, type = 1)
}
