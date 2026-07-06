package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for buildThreadImageUris() — the flat, chronological, whole-thread image list
 * that backs the full-screen viewer's thread-wide swipe (swiping moves to the next/
 * previous image across message boundaries, not just within one message's attachments).
 */
class ThreadImageUrisTest {

    private fun msg(id: Long, ts: Long, attachments: List<MessageAttachment> = emptyList()) = Message(
        id = id, threadId = 1L, address = "+1", body = "", timestamp = ts, isSent = false,
        type = 1, isMms = attachments.isNotEmpty(), attachments = attachments
    )

    private fun image(uri: String) = MessageAttachment(uri, "image/jpeg")
    private fun video(uri: String) = MessageAttachment(uri, "video/mp4")
    private fun audio(uri: String) = MessageAttachment(uri, "audio/amr")

    @Test fun `empty message list returns empty uri list`() {
        assertTrue(buildThreadImageUris(emptyList()).isEmpty())
    }

    @Test fun `messages with no attachments return empty uri list`() {
        val messages = listOf(msg(1, 0), msg(2, 1000))
        assertTrue(buildThreadImageUris(messages).isEmpty())
    }

    @Test fun `single image in a single message is returned`() {
        val messages = listOf(msg(1, 0, listOf(image("content://mms/part/1"))))
        assertEquals(listOf("content://mms/part/1"), buildThreadImageUris(messages))
    }

    @Test fun `images across multiple messages are flattened in message order`() {
        // Regression target: swiping across the whole thread, not just one message's attachments.
        val messages = listOf(
            msg(1, 0,    listOf(image("content://mms/part/1"))),
            msg(2, 1000, listOf(image("content://mms/part/2"), image("content://mms/part/3"))),
            msg(3, 2000, listOf(image("content://mms/part/4")))
        )
        assertEquals(
            listOf(
                "content://mms/part/1",
                "content://mms/part/2",
                "content://mms/part/3",
                "content://mms/part/4"
            ),
            buildThreadImageUris(messages)
        )
    }

    @Test fun `video and audio attachments are excluded`() {
        val messages = listOf(
            msg(1, 0, listOf(image("content://mms/part/1"), video("content://mms/part/2"))),
            msg(2, 1000, listOf(audio("content://mms/part/3")))
        )
        assertEquals(listOf("content://mms/part/1"), buildThreadImageUris(messages))
    }

    @Test fun `mime type matching is case-insensitive`() {
        val messages = listOf(msg(1, 0, listOf(MessageAttachment("content://mms/part/1", "IMAGE/JPEG"))))
        assertEquals(listOf("content://mms/part/1"), buildThreadImageUris(messages))
    }

    @Test fun `order follows input order, not attachment id or uri`() {
        val messages = listOf(
            msg(1, 0, listOf(image("content://mms/part/99"))),
            msg(2, 1000, listOf(image("content://mms/part/1")))
        )
        assertEquals(
            listOf("content://mms/part/99", "content://mms/part/1"),
            buildThreadImageUris(messages)
        )
    }
}
