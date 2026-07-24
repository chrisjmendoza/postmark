package com.plusorminustwo.postmark.data.reaction

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses Android (Google Messages / Samsung) reaction fallback SMS messages.
 *
 * Android format:
 *   [emoji] to "[quoted text]"
 *   [emoji] to "[quoted text]" removed
 *   Removed [emoji] from "[quoted text]"
 *
 * The last form is the actual on-device removal shape (captured 2026-07-24) — unlike the
 * suffix form's trailing "removed", a genuine removal archives as a "Removed … from" PREFIX,
 * not a "… removed" suffix. Both are recognised; whichever a given Android build emits, this
 * parser handles it.
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

    // Matches the removal-PREFIX shape: Removed <emoji> from <open-quote><text><close-quote>
    // "Removed" and "from" are literal English keywords, same limitation as the "to" literal
    // above — a non-English Android locale would need its own pattern. Same emoji guard and
    // quote class as reactionRegex; DOT_MATCHES_ALL + the ^…$ anchors let the non-greedy quote
    // capture backtrack out to the LAST closing quote in the body, so embedded quotes inside
    // the quoted text (as in the real device sample) survive intact.
    private val removalPrefixRegex = Regex(
        """^Removed\s+(\S{1,8})\s+from\s+[“”‘’„«»"'](.+?)[“”‘’„«»"']\s*$""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * Attempts to parse [messageBody] as an Android reaction fallback SMS.
     *
     * @return A [ParsedReaction] if the body matches the expected format, or `null`.
     */
    fun parse(messageBody: String): ParsedReaction? {
        val trimmed = messageBody.trim()

        reactionRegex.find(trimmed)?.let { match ->
            val emoji = match.groupValues[1]
            // Reject anything whose first character is plain ASCII — that cannot be an emoji.
            if (emoji[0].code <= 127) return null
            val quotedText = match.groupValues[2].trim()
            val isRemoval = match.groupValues[3].isNotBlank()
            return ParsedReaction(emoji, quotedText, isRemoval)
        }

        removalPrefixRegex.find(trimmed)?.let { match ->
            val emoji = match.groupValues[1]
            if (emoji[0].code <= 127) return null
            val quotedText = match.groupValues[2].trim()
            return ParsedReaction(emoji, quotedText, isRemoval = true)
        }

        return null
    }
}
