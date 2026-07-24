package com.plusorminustwo.postmark.domain.reaction

import com.plusorminustwo.postmark.data.reaction.AndroidReactionParser
import com.plusorminustwo.postmark.data.reaction.AppleReactionParser
import com.plusorminustwo.postmark.data.reaction.ReactionFallbackParser
import com.plusorminustwo.postmark.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the outbound reaction fallback: [composeReactionFallback] builds the
 * wire string and [reactionFallbackRoundTrips] gates the send by re-parsing + re-matching it
 * through the REAL [ReactionFallbackParser] (the same code that resolves inbound fallbacks),
 * so a case only "sends" here if it would resolve correctly on the recipient's phone.
 */
class OutboundReactionFallbackTest {

    private val parser = ReactionFallbackParser(
        AndroidReactionParser(),
        AppleReactionParser(patternsProvider = {
            listOf(AppleReactionParser.ReactionPattern("👍", listOf("Liked"), listOf("Removed a like from")))
        })
    )

    // ── composeReactionFallback shape ──────────────────────────────────────────

    @Test fun `short body is quoted whole with no ellipsis`() {
        assertEquals("👍 to \"ok sounds good\"", composeReactionFallback("👍", "ok sounds good", false))
    }

    @Test fun `removal variant appends removed`() {
        assertEquals("👍 to \"ok sounds good\" removed", composeReactionFallback("👍", "ok sounds good", true))
    }

    @Test fun `long body is truncated to the budget with an ellipsis`() {
        val url = "https://example.com/some/really/long/path?utm=abcdef&x=1234567890"
        val composed = composeReactionFallback("❤️", url, false)!!
        // Quote is the first OUTBOUND_QUOTE_BUDGET chars + a single ellipsis char.
        assertEquals("❤️ to \"${url.take(OUTBOUND_QUOTE_BUDGET)}…\"", composed)
    }

    @Test fun `multiline body is single-line-ified at the first newline inside the budget`() {
        // First line "Hey there" fits the budget, so the quote is just that line (no ellipsis).
        val composed = composeReactionFallback("👍", "Hey there\nlots more text on the next line", false)
        assertEquals("👍 to \"Hey there\"", composed)
    }

    @Test fun `quote whose stem falls below the min stem returns null`() {
        // 30-char budget window is almost all trailing whitespace → stem < 10 → uncomposable.
        val body = "abc" + " ".repeat(40) + "tail"
        assertNull(composeReactionFallback("👍", body, false))
    }

    @Test fun `blank body returns null`() {
        assertNull(composeReactionFallback("👍", "   ", false))
    }

    @Test fun `body starting with a newline returns null`() {
        assertNull(composeReactionFallback("👍", "\nactual content here", false))
    }

    // ── round-trip gating through the real parser ──────────────────────────────

    @Test fun `short body round-trips to the exact target`() {
        val target = msg(1, "ok sounds good")
        assertTrue(roundTrips("👍", target, false, listOf(target)))
    }

    @Test fun `removal round-trips to the exact target`() {
        val target = msg(1, "ok sounds good")
        assertTrue(roundTrips("👍", target, true, listOf(target)))
    }

    @Test fun `long url round-trips via the truncated-quote strategy`() {
        // The Tonya case: a ~70-char https link. Composed quote is ellipsized, so only the
        // parser's truncated-quote (ellipsis-stripped prefix) strategy can resolve it.
        val target = msg(1, "https://maps.example.com/place/Tonya+Bakery/@37.42,-122.08,17z/data=xyz")
        assertTrue(roundTrips("❤️", target, false, listOf(target)))
    }

    @Test fun `multiline body round-trips via the prefix strategy`() {
        val target = msg(1, "Dinner plans:\n- pizza\n- salad\n- ice cream")
        assertTrue(roundTrips("😂", target, false, listOf(target)))
    }

    @Test fun `body with embedded double quotes round-trips to the exact target`() {
        // The parser backtracks (.+?) to the LAST quote, so the whole body (inner quotes and
        // all) is recovered and exact-matches.
        val target = msg(1, "She said \"hi\" to me")
        assertTrue(roundTrips("👍", target, false, listOf(target)))
    }

    @Test fun `ambiguous short first line does NOT send`() {
        // Target's first line "Hi" is shorter than the min stem and a NEWER message also
        // starts with "Hi", so the quote resolves to the wrong (newest) message → local-only.
        val target = msgAt(1, "Hi\nthe long actual content of this particular message", 1000L)
        val newer = msgAt(2, "Hi there, unrelated", 2000L)
        assertFalse(roundTrips("👍", target, false, listOf(target, newer)))
    }

    @Test fun `family ZWJ emoji fits the parser and sends`() {
        // The plain family emoji is 7 Unicode code points (the parser's \S{1,8} counts code
        // points, not UTF-16 units, since Java regex matches surrogate pairs atomically), so it
        // round-trips cleanly and DOES send — a valid reaction, not a broken one.
        val target = msg(1, "ok sounds good")
        assertTrue(roundTrips("👨‍👩‍👧‍👦", target, false, listOf(target)))
    }

    @Test fun `over-long ZWJ emoji is left local-only`() {
        // A skin-toned family (11 code points) exceeds the parser's \S{1,8}, so the composed
        // string can't parse back → the gate refuses to send it, keeping it local-only.
        val target = msg(1, "ok sounds good")
        val overLong = "👨🏻‍👩🏻‍👧🏻‍👦🏻"
        val composed = composeReactionFallback(overLong, target.body, false)
        assertTrue(composed != null) // compose is lenient; it doesn't know the parser's regex
        assertFalse(roundTrips(overLong, target, false, listOf(target))) // ...the gate isn't
    }

    @Test fun `duplicate target text resolving to a different id does NOT send`() {
        val target = msgAt(1, "See you tomorrow", 1000L)
        val newerDuplicate = msgAt(2, "See you tomorrow", 2000L)
        // findOriginalMessage returns the NEWEST match (id 2), not our target (id 1).
        assertFalse(roundTrips("👍", target, false, listOf(target, newerDuplicate)))
    }

    @Test fun `unresolvable quote does NOT send`() {
        val target = msg(1, "ok sounds good")
        // Candidate pool without the target: nothing matches → local-only.
        val other = msg(2, "completely different message")
        assertFalse(roundTrips("👍", target, false, listOf(other)))
    }

    // ── shouldRequeueOrphanedReactionFallback (risk-C recovery gate) ────────────

    @Test fun `requeue when no row, is fallback, and address+body present`() {
        assertTrue(shouldRequeueOrphanedReactionFallback(false, true, "+15551234", "👍 to \"hi\""))
    }

    @Test fun `no requeue when a room row still exists`() {
        assertFalse(shouldRequeueOrphanedReactionFallback(true, true, "+15551234", "👍 to \"hi\""))
    }

    @Test fun `no requeue when body is not a fallback`() {
        assertFalse(shouldRequeueOrphanedReactionFallback(false, false, "+15551234", "just a normal text"))
    }

    @Test fun `no requeue when address or body missing`() {
        assertFalse(shouldRequeueOrphanedReactionFallback(false, true, null, "👍 to \"hi\""))
        assertFalse(shouldRequeueOrphanedReactionFallback(false, true, "+15551234", null))
        assertFalse(shouldRequeueOrphanedReactionFallback(false, true, "", "👍 to \"hi\""))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Composes a fallback for [target] and gates it exactly as the ViewModel does. */
    private fun roundTrips(emoji: String, target: Message, isRemoval: Boolean, pool: List<Message>): Boolean {
        val composed = composeReactionFallback(emoji, target.body, isRemoval)
        // Mirror the ViewModel: exclude only reaction fallbacks; the target stays in the pool.
        val candidates = pool.filter { !parser.isReactionFallback(it.body) }
        return reactionFallbackRoundTrips(
            composed = composed,
            targetMessageId = target.id,
            quoteOf = { parser.parse(it)?.quotedText },
            findOriginalId = { parser.findOriginalMessage(it, candidates)?.id },
        )
    }

    private fun msg(id: Long, body: String) =
        Message(id = id, threadId = 1L, address = "+1", body = body, timestamp = id * 1000L, isSent = false, type = 1)

    private fun msgAt(id: Long, body: String, timestamp: Long) =
        Message(id = id, threadId = 1L, address = "+1", body = body, timestamp = timestamp, isSent = false, type = 1)
}
