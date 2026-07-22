package com.plusorminustwo.postmark.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MessageReactionsTest {

    private fun msg(id: Long) = Message(
        id = id, threadId = 1L, address = "+1", body = "m$id",
        timestamp = id, isSent = false, type = 1
    )

    private fun reaction(messageId: Long, emoji: String) = Reaction(
        id = 0L, messageId = messageId, senderAddress = "+1",
        emoji = emoji, timestamp = 0L, rawText = ""
    )

    @Test
    fun `attaches reactions to the matching message by id`() {
        val messages = listOf(msg(1), msg(2))
        val reactions = listOf(reaction(2, "❤️"))

        val result = attachReactions(messages, reactions)

        assertEquals(emptyList<Reaction>(), result[0].reactions)
        assertEquals(listOf("❤️"), result[1].reactions.map { it.emoji })
    }

    @Test
    fun `groups multiple reactions onto the same message`() {
        val messages = listOf(msg(1))
        val reactions = listOf(reaction(1, "❤️"), reaction(1, "👍"))

        val result = attachReactions(messages, reactions)

        assertEquals(listOf("❤️", "👍"), result[0].reactions.map { it.emoji })
    }

    @Test
    fun `ignores reactions whose messageId is not in the result set`() {
        val messages = listOf(msg(1))
        val reactions = listOf(reaction(99, "❤️"))

        val result = attachReactions(messages, reactions)

        assertEquals(emptyList<Reaction>(), result[0].reactions)
    }

    @Test
    fun `returns the same list instance when there are no reactions`() {
        val messages = listOf(msg(1), msg(2))

        val result = attachReactions(messages, emptyList())

        assertSame(messages, result)
    }
}
