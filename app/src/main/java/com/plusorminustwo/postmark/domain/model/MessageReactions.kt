package com.plusorminustwo.postmark.domain.model

/**
 * Attaches [reactions] to their parent [messages] by matching on [Reaction.messageId].
 *
 * Pure in-memory join — the single mechanism used by every read path that needs
 * reactions on messages (thread view and search results alike), so the two never
 * drift. Messages with no matching reaction keep whatever list they already carry.
 */
fun attachReactions(messages: List<Message>, reactions: List<Reaction>): List<Message> {
    if (reactions.isEmpty()) return messages
    val byMessage = reactions.groupBy { it.messageId }
    return messages.map { message ->
        byMessage[message.id]?.let { message.copy(reactions = it) } ?: message
    }
}
