package com.plusorminustwo.postmark.data.reaction

import com.plusorminustwo.postmark.domain.model.Message
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ReactionFallbackParser]'s matching pipeline — findOriginalMessage and
 * processIncomingMessage. This is the single matcher shared by the Android and Apple
 * fallback formats (the per-format classes only recognise the textual shape).
 *
 * Strategy order under test: exact → Unicode-normalized → prefix → truncated-quote
 * (ellipsis-stripped prefix). The truncated-quote strategy is the July 2026 fix for
 * reactions quoting long originals — links especially — which the sending platform
 * ellipsizes inside the quotes, so no earlier strategy can ever match them.
 */
class ReactionFallbackParserMatchTest {

    private lateinit var parser: ReactionFallbackParser

    @Before fun setUp() {
        parser = ReactionFallbackParser(
            AndroidReactionParser(),
            AppleReactionParser(patternsProvider = {
                listOf(AppleReactionParser.ReactionPattern("👍", listOf("Liked"), listOf("Removed a like from")))
            })
        )
    }

    // ── strategy order ─────────────────────────────────────────────────────

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

    // ── truncated-quote strategy (ellipsized long originals) ───────────────

    @Test fun `ellipsized link quote matches the full-URL original`() {
        // The exact failure from the July 2026 report: a ❤️ to a music link arrived as
        // `❤️ to "https://music.youtube.com/watch?v=ZKeroWatXDQ&si=F…"` while the
        // original body was the untruncated URL — no strategy could match because the
        // quote ends with an ellipsis the original doesn't contain.
        val original = messageAt(1, "https://music.youtube.com/watch?v=ZKeroWatXDQ&si=F19hMsTBwf0n8HvQ", 1000L)
        val result = parser.findOriginalMessage(
            "https://music.youtube.com/watch?v=ZKeroWatXDQ&si=F…",
            listOf(original)
        )
        assertEquals(1L, result?.id)
    }

    @Test fun `ascii three-dot truncation also matches`() {
        val original = messageAt(1, "https://music.youtube.com/watch?v=ZKeroWatXDQ&si=F19hMsTBwf0n8HvQ", 1000L)
        val result = parser.findOriginalMessage(
            "https://music.youtube.com/watch?v=ZKeroWatXDQ&si=F...",
            listOf(original)
        )
        assertEquals(1L, result?.id)
    }

    @Test fun `short ellipsized stem does not match arbitrary messages`() {
        // Below the stem-length floor the strategy must stay inert — otherwise
        // `ok…` would latch onto whatever recent message happens to start with "ok".
        val candidate = messageAt(1, "ok so here is the plan for tomorrow", 1000L)
        assertNull(parser.findOriginalMessage("ok…", listOf(candidate)))
    }

    @Test fun `original that literally ends with an ellipsis still exact-matches`() {
        // A user-typed trailing "…" is not truncation; exact match runs first and the
        // truncated-quote strategy never fires.
        val candidate = messageAt(1, "well that happened…", 1000L)
        val result = parser.findOriginalMessage("well that happened…", listOf(candidate))
        assertEquals(1L, result?.id)
    }

    @Test fun `ellipsized quote resolves end to end through processIncomingMessage`() {
        val original = messageAt(1, "https://music.youtube.com/watch?v=ZKeroWatXDQ&si=F19hMsTBwf0n8HvQ", 1000L)
        val fallback = messageAt(99, "❤️ to \"https://music.youtube.com/watch?v=ZKeroWatXDQ&si=F…\"", 2000L)
        val reaction = parser.processIncomingMessage(fallback, listOf(original, fallback), "+15550001")
        assertNotNull(reaction)
        assertEquals(1L, reaction!!.messageId)
        assertEquals("❤️", reaction.emoji)
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

    @Test fun `processIncomingMessage does not produce dangling reaction pointing at itself`() {
        // Core self-match regression: when only the reaction message is in candidates,
        // nothing may match — the reaction body contains the quoted text literally.
        val incoming = messageAt(99, """❤️ to "Yeah, my body really likes bike riding"""", 5000L)
        assertNull(parser.processIncomingMessage(incoming, listOf(incoming), "+15550001"))
    }

    @Test fun `reaction body itself is not matched via contains`() {
        // The fuzzy contains strategy stays removed: a reaction body containing the
        // quoted text literally must not be treated as the original.
        val reactionMsg = messageAt(99, """👍 to "Fine but you're cooking breakfast"""", 5000L)
        assertNull(parser.findOriginalMessage("Fine but you're cooking breakfast", listOf(reactionMsg)))
    }

    // ── newest-to-oldest search order ──────────────────────────────────────

    @Test fun `returns newest match when duplicates exist`() {
        val older = messageAt(1, "Let's go!", 1000L)
        val newer = messageAt(2, "Let's go!", 2000L)
        assertEquals(2L, parser.findOriginalMessage("Let's go!", listOf(older, newer))?.id)
    }

    @Test fun `candidates are re-sorted newest first internally`() {
        val old = messageAt(1, "See you tomorrow", 1000L)
        val recent = messageAt(2, "See you tomorrow", 9000L)
        // Candidates given in oldest-first order — parser must re-sort internally.
        assertEquals(2L, parser.findOriginalMessage("See you tomorrow", listOf(old, recent))?.id)
    }

    // ── 100-message window ─────────────────────────────────────────────────

    @Test fun `returns null when match is beyond 100 messages`() {
        val oldest = messageAt(0, "target message", 0L)
        val others = (1..100).map { i -> messageAt(i.toLong(), "msg $i", i * 1000L) }
        assertNull(parser.findOriginalMessage("target message", listOf(oldest) + others))
    }

    @Test fun `returns match when it is exactly at position 100`() {
        val target = messageAt(1, "target message", 1000L)
        val others = (2..100).map { i -> messageAt(i.toLong(), "msg $i", i * 1000L) }
        assertNotNull(parser.findOriginalMessage("target message", listOf(target) + others))
    }

    // ── Unicode normalization ──────────────────────────────────────────────

    @Test fun `smart apostrophe in candidate matches straight apostrophe in query`() {
        val candidate = messageAt(1, "I’m built for it", 1000L)
        assertEquals(1L, parser.findOriginalMessage("I'm built for it", listOf(candidate))?.id)
    }

    @Test fun `straight apostrophe in candidate matches smart apostrophe in query`() {
        val candidate = messageAt(1, "I'm built for it", 1000L)
        assertNotNull(parser.findOriginalMessage("I’m built for it", listOf(candidate)))
    }

    @Test fun `smart double quotes match straight quotes`() {
        val candidate = messageAt(1, "He said \"hello\"", 1000L)
        assertNotNull(parser.findOriginalMessage("He said “hello”", listOf(candidate)))
    }

    @Test fun `ellipsis character matches three dots`() {
        val candidate = messageAt(1, "Wait...", 1000L)
        assertNotNull(parser.findOriginalMessage("Wait…", listOf(candidate)))
    }

    @Test fun `em dash matches hyphen`() {
        val candidate = messageAt(1, "rock - paper", 1000L)
        assertNotNull(parser.findOriginalMessage("rock — paper", listOf(candidate)))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun messages(vararg bodies: String) = bodies.mapIndexed { i, body -> message(i.toLong(), body) }

    private fun message(id: Long, body: String) =
        Message(id = id, threadId = 1L, address = "+1", body = body, timestamp = 0L, isSent = false, type = 1)

    private fun messageAt(id: Long, body: String, timestamp: Long) =
        Message(id = id, threadId = 1L, address = "+1", body = body, timestamp = timestamp, isSent = false, type = 1)
}
