package com.plusorminustwo.postmark.search.parser

import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Reaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses Android (Google Messages / Samsung) reaction fallback SMS messages.
 *
 * Android format:
 *   [emoji] to "[quoted text]"
 *   [emoji] to "[quoted text]" removed
 *
 * The emoji is used directly as the reaction — no verb-to-emoji mapping is needed.
 *
 * Quote character variants handled: " " ' ' „ " « »
 */
@Singleton
class AndroidReactionParser @Inject constructor() {

    // Matches: <emoji> to <open-quote><text><close-quote>[ removed]
    // The emoji is 1–8 non-whitespace characters whose first codepoint must be non-ASCII
    // (i.e. an actual emoji — never a plain English word).
    // Quote class covers: " " ' ' „ " « »
    private val reactionRegex = Regex(
        """^(\S{1,8})\s+to\s+[\u201C\u201D\u2018\u2019\u201E\u00AB\u00BB"'](.+?)[\u201C\u201D\u2018\u2019\u201E\u00AB\u00BB"']\s*(\bremoved\b)?\s*$""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * Attempts to parse [messageBody] as an Android reaction fallback SMS.
     *
     * @return A [ParsedReaction] if the body matches the expected format, or `null`.
     */
    fun parse(messageBody: String): ParsedReaction? {
        val trimmed = messageBody.trim()
        val match = reactionRegex.find(trimmed) ?: return null
        val emoji = match.groupValues[1]
        val quotedText = match.groupValues[2].trim()
        val isRemoval = match.groupValues[3].isNotBlank()

        // Reject anything whose first character is plain ASCII — that cannot be an emoji.
        if (emoji[0].code <= 127) return null

        return ParsedReaction(emoji, quotedText, isRemoval)
    }

    // ── Matching helpers (internal — used by unit tests via AndroidReactionParser) ──────────
    // These mirror the implementations in ReactionFallbackParser so that pure-function tests
    // can run without needing a Context (required by AppleReactionParser).

    /** Normalises smart quotes, em-dashes, and ellipsis to their ASCII equivalents. */
    internal fun normalize(text: String): String = text
        .replace('\u2019', '\'').replace('\u2018', '\'')
        .replace('\u201C', '"').replace('\u201D', '"')
        .replace("\u2026", "...")
        .replace('\u2014', '-').replace('\u2013', '-')

    /**
     * Searches [candidates] for the message that [quotedText] refers to.
     * Tries exact → normalized → prefix, newest-first, within 100 messages.
     */
    internal fun findOriginalMessage(quotedText: String, candidates: List<Message>): Message? {
        val trimmedQuery = quotedText.trim()
        if (trimmedQuery.isEmpty()) return null

        val searchWindow = candidates
            .sortedByDescending { it.timestamp }
            .take(100)

        // 1. Exact match (case-insensitive, trimmed)
        searchWindow.firstOrNull { it.body.trim().equals(trimmedQuery, ignoreCase = true) }
            ?.let { return it }

        // 2. Unicode-normalized match
        val normalizedQuery = normalize(trimmedQuery)
        searchWindow.firstOrNull { normalize(it.body.trim()).equals(normalizedQuery, ignoreCase = true) }
            ?.let { return it }

        // 3. Prefix match
        searchWindow.firstOrNull {
            val trimmedBody = it.body.trim()
            trimmedBody.startsWith(trimmedQuery, ignoreCase = true) ||
            normalize(trimmedBody).startsWith(normalizedQuery, ignoreCase = true)
        }?.let { return it }

        return null
    }

    /**
     * Resolves a reaction fallback [message] to a [Reaction] entity.
     * Returns a [Reaction] for both additions and removals (when the original is found),
     * so the caller can decide whether to insert or delete. Returns null if unresolved.
     */
    internal fun processIncomingMessage(
        message: Message,
        threadMessages: List<Message>,
        senderAddress: String
    ): Reaction? {
        val parsed = parse(message.body) ?: return null
        // Exclude the reaction message itself and any Android-format reaction fallbacks
        // from the candidate pool to prevent self-match and cross-match.
        val candidates = threadMessages.filter {
            it.id != message.id && parse(it.body) == null
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
