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

    fun findOriginalMessage(quotedText: String, candidates: List<Message>): Message? =
        androidParser.findOriginalMessage(quotedText, candidates)

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
