package com.plusorminustwo.postmark.data.reaction

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
 *
 * Matching a parsed fallback to its original message lives in [ReactionFallbackParser]
 * (findOriginalMessage / processIncomingMessage) — the single implementation shared by
 * both fallback formats. This class only recognises the Android textual format.
 */
@Singleton
class AndroidReactionParser @Inject constructor() {

    // Matches: <emoji> to <open-quote><text><close-quote>[ removed]
    // The emoji is 1–8 non-whitespace characters whose first codepoint must be non-ASCII
    // (i.e. an actual emoji — never a plain English word).
    // Quote class covers: " " ' ' „ " « »
    private val reactionRegex = Regex(
        """^(\S{1,8})\s+to\s+[“”‘’„«»"'](.+?)[“”‘’„«»"']\s*(\bremoved\b)?\s*$""",
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
}
