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

    fun parse(messageBody: String): ParsedReaction? {
        val trimmed = messageBody.trim()
        val match = reactionRegex.find(trimmed) ?: return null
        val emoji = match.groupValues[1]
        val quotedText = match.groupValues[2]
        val isRemoval = match.groupValues[3].isNotBlank()

        // Reject anything whose first character is plain ASCII — that cannot be an emoji.
        if (emoji[0].code <= 127) return null

        return ParsedReaction(emoji, quotedText, isRemoval)
    }

    fun findOriginalMessage(quotedText: String, candidates: List<Message>): Message? {
        val lower = quotedText.lowercase()
        return candidates.firstOrNull { it.body.equals(quotedText, ignoreCase = true) }
            ?: candidates.firstOrNull { it.body.startsWith(quotedText, ignoreCase = true) }
            ?: candidates.firstOrNull { it.body.lowercase().contains(lower) }
    }

    fun processIncomingMessage(
        message: Message,
        threadMessages: List<Message>,
        senderAddress: String
    ): Reaction? {
        val parsed = parse(message.body) ?: return null
        val candidates = threadMessages.filter { it.id != message.id }
        val original = findOriginalMessage(parsed.quotedText, candidates) ?: return null
        if (parsed.isRemoval) return null

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
