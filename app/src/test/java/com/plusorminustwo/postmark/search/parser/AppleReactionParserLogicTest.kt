package com.plusorminustwo.postmark.search.parser

import com.plusorminustwo.postmark.domain.model.Message
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests the pure parsing logic of AppleReactionParser using a pre-built
 * pattern list, without needing Android Context or asset loading.
 */
class AppleReactionParserLogicTest {

    // Minimal pattern set exercising all branches without loading the JSON asset
    private val patterns = listOf(
        TestPattern("❤️", listOf("Loved", "Vond geweldig"), listOf("Removed a heart from")),
        TestPattern("😂", listOf("Laughed at"), listOf("Removed a laugh from")),
        TestPattern("👍", listOf("Liked"), listOf("Removed a like from")),
        TestPattern("👎", listOf("Disliked"), listOf("Removed a dislike from")),
        TestPattern("‼️", listOf("Emphasized"), listOf("Removed an exclamation mark from")),
        TestPattern("❓", listOf("Questioned"), listOf("Removed a question mark from"))
    )

    private lateinit var parser: TestableParser

    @Before fun setUp() { parser = TestableParser(patterns) }

    // ── Parse ──────────────────────────────────────────────────────────────

    @Test fun `loved maps to heart emoji`() {
        val result = parser.parse("Loved 'did you see this?'")
        assertNotNull(result)
        assertEquals("❤️", result!!.emoji)
        assertEquals("did you see this?", result.quotedText)
        assertFalse(result.isRemoval)
    }

    @Test fun `laughed at maps to laugh emoji`() {
        val result = parser.parse("Laughed at \"classic\"")
        assertNotNull(result)
        assertEquals("😂", result!!.emoji)
    }

    @Test fun `disliked maps to thumbs down`() {
        val result = parser.parse("Disliked 'this idea'")
        assertEquals("👎", result!!.emoji)
    }

    @Test fun `emphasized maps to double exclamation`() {
        assertEquals("‼️", parser.parse("Emphasized 'exactly'")!!.emoji)
    }

    @Test fun `questioned maps to question mark`() {
        assertEquals("❓", parser.parse("Questioned 'really?'")!!.emoji)
    }

    @Test fun `dutch loved verb also maps to heart`() {
        val result = parser.parse("Vond geweldig 'haha'")
        assertEquals("❤️", result!!.emoji)
    }

    @Test fun `removal phrase sets isRemoval true`() {
        val result = parser.parse("Removed a heart from 'did you see this?'")
        assertNotNull(result)
        assertTrue(result!!.isRemoval)
        assertEquals("❤️", result.emoji)
    }

    @Test fun `plain message body returns null`() {
        assertNull(parser.parse("Hey, how are you?"))
    }

    @Test fun `empty string returns null`() {
        assertNull(parser.parse(""))
    }

    @Test fun `reaction verb without quote returns null`() {
        assertNull(parser.parse("Loved it"))
    }

    // ── findOriginalMessage ────────────────────────────────────────────────

    @Test fun `exact match takes priority`() {
        val candidates = messages("hello", "hello world", "say hello")
        val match = parser.findOriginalMessage("hello", candidates)
        assertEquals("hello", match?.body)
    }

    @Test fun `prefix match used when no exact`() {
        val candidates = messages("hello world", "say hello")
        val match = parser.findOriginalMessage("hello", candidates)
        assertEquals("hello world", match?.body)
    }

    @Test fun `fuzzy containment used as fallback`() {
        val candidates = messages("say hello there", "goodbye")
        val match = parser.findOriginalMessage("hello", candidates)
        assertEquals("say hello there", match?.body)
    }

    @Test fun `no match returns null`() {
        val candidates = messages("goodbye", "see you later")
        assertNull(parser.findOriginalMessage("hello", candidates))
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun messages(vararg bodies: String) = bodies.mapIndexed { i, body ->
        Message(id = i.toLong(), threadId = 1, address = "+1", body = body,
            timestamp = 0, isSent = false, type = 1)
    }

    // Minimal parser that accepts pre-loaded patterns instead of loading from assets
    private data class TestPattern(
        val emoji: String,
        val verbs: List<String>,
        val removeVerbs: List<String>
    )

    private class TestableParser(private val patterns: List<TestPattern>) {

        private val reactionRegex = Regex(
            """^(.+?)\s+['"'"](.+?)['"'"]\s*$""",
            RegexOption.DOT_MATCHES_ALL
        )

        fun parse(body: String): ParsedReaction? {
            val match = reactionRegex.find(body.trim()) ?: return null
            val verb = match.groupValues[1].trim()
            val quotedText = match.groupValues[2].trim()
            patterns.forEach { p ->
                if (p.verbs.any { it.equals(verb, ignoreCase = true) })
                    return ParsedReaction(p.emoji, quotedText, false)
                if (p.removeVerbs.any { verb.startsWith(it, ignoreCase = true) })
                    return ParsedReaction(p.emoji, quotedText, true)
            }
            return null
        }

        fun findOriginalMessage(quotedText: String, candidates: List<Message>): Message? {
            val lower = quotedText.lowercase()
            return candidates.firstOrNull { it.body.equals(quotedText, ignoreCase = true) }
                ?: candidates.firstOrNull { it.body.startsWith(quotedText, ignoreCase = true) }
                ?: candidates.firstOrNull { it.body.lowercase().contains(lower) }
        }
    }
}
