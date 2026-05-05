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
        // Search newest-to-oldest, capped at 100 messages — reactions referring to older
        // messages are treated as unresolvable and rendered as normal bubbles.
        val searchWindow = candidates
            .sortedByDescending { it.timestamp }
            .take(100)

        // 1. Exact match (case-insensitive)
        searchWindow.firstOrNull { it.body.equals(quotedText, ignoreCase = true) }
            ?.let { return it }

        // 2. Normalized match — handles apostrophe/quote mismatches between
        //    Apple (U+2019 right single quote) and Android (U+0027 straight apostrophe)
        //    and other common Unicode substitutions between platforms.
        val normalizedQuery = normalize(quotedText)
        searchWindow.firstOrNull { normalize(it.body).equals(normalizedQuery, ignoreCase = true) }
            ?.let { return it }

        // 3. Prefix match — reaction may quote only the start of a long message
        searchWindow.firstOrNull {
            it.body.startsWith(quotedText, ignoreCase = true) ||
            normalize(it.body).startsWith(normalizedQuery, ignoreCase = true)
        }?.let { return it }

        // Deliberately no .contains() match — too broad and causes self-matching
        // where the reaction message body contains the quoted text literally.
        return null
    }

    /**
     * Normalizes known Unicode substitutions that differ between Apple and Android keyboards:
     * smart quotes → straight quotes, ellipsis → "...", em/en dash → "-".
     */
    internal fun normalize(text: String): String = text
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
