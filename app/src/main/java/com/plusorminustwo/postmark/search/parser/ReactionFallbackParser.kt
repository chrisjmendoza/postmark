package com.plusorminustwo.postmark.search.parser

import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Reaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified reaction fallback parser that tries Android format first, then Apple format.
 *
 * Android: `👍 to "quoted text"` / `👍 to "quoted text" removed`
 * Apple:   `Liked 'quoted text'` / `Removed a like from 'quoted text'`
 *
 * Use this class instead of calling [AndroidReactionParser] or [AppleReactionParser] directly.
 */
@Singleton
class ReactionFallbackParser @Inject constructor(
    private val androidParser: AndroidReactionParser,
    private val appleParser: AppleReactionParser
) {
    /** Returns a [ParsedReaction] if the body matches either format, or null. */
    fun parse(body: String): ParsedReaction? = androidParser.parse(body) ?: appleParser.parse(body)

    /** Returns true if this message body is a reaction fallback and should NOT be a visible bubble. */
    fun isReactionFallback(body: String): Boolean = parse(body) != null

    /**
     * Searches [candidates] for the message that [quotedText] refers to.
     *
     * Tries exact match, then Unicode-normalized match (handling Apple/Android
     * quote variants), then prefix match. Searches the 100 most-recent candidates
     * newest-first. Returns `null` if no match is found in that window.
     */
    fun findOriginalMessage(quotedText: String, candidates: List<Message>): Message? {
        val trimmedQuery = quotedText.trim()
        if (trimmedQuery.isEmpty()) return null

        // Search newest-to-oldest, capped at 100 messages — reactions referring to older
        // messages are treated as unresolvable and rendered as normal bubbles.
        val searchWindow = candidates
            .sortedByDescending { it.timestamp }
            .take(100)

        // 1. Exact match (case-insensitive, trimmed)
        searchWindow.firstOrNull { it.body.trim().equals(trimmedQuery, ignoreCase = true) }
            ?.let { return it }

        // 2. Normalized match — handles apostrophe/quote mismatches between
        //    Apple (U+2019 right single quote) and Android (U+0027 straight apostrophe)
        //    and other common Unicode substitutions between platforms.
        val normalizedQuery = normalize(trimmedQuery)
        searchWindow.firstOrNull { normalize(it.body.trim()).equals(normalizedQuery, ignoreCase = true) }
            ?.let { return it }

        // 3. Prefix match — reaction may quote only the start of a long message
        searchWindow.firstOrNull {
            val trimmedBody = it.body.trim()
            trimmedBody.startsWith(trimmedQuery, ignoreCase = true) ||
            normalize(trimmedBody).startsWith(normalizedQuery, ignoreCase = true)
        }?.let { return it }

        return null
    }

    private fun normalize(text: String): String = text
        .replace('\u2019', '\'').replace('\u2018', '\'')
        .replace('\u201C', '"').replace('\u201D', '"')
        .replace("\u2026", "...")
        .replace('\u2014', '-').replace('\u2013', '-')

    fun processIncomingMessage(
        message: Message,
        threadMessages: List<Message>,
        senderAddress: String
    ): Reaction? {
        val parsed = parse(message.body) ?: return null
        // Exclude the reaction message itself and any other reaction fallbacks from the
        // candidate pool. Reaction bodies contain the quoted text literally, which would
        // cause a self-match or cross-match via the prefix/normalized strategies.
        val candidates = threadMessages.filter {
            it.id != message.id && !isReactionFallback(it.body)
        }
        val original = findOriginalMessage(parsed.quotedText, candidates) ?: return null

        return Reaction(
            id = 0,
            messageId = original.id,
            senderAddress = senderAddress,
            emoji = parsed.emoji,
            timestamp = message.timestamp,
            rawText = message.body
        )
    }
}
