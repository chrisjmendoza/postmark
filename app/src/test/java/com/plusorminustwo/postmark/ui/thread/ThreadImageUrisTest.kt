package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Tests for buildThreadImages() — the flat, chronological, whole-thread image list that
 * backs the full-screen viewer's thread-wide swipe, date pill, and "go to chat" jump
 * (swiping moves to the next/previous image across message boundaries, not just within
 * one message's attachments).
 */
class ThreadImageUrisTest {

    private fun msg(
        id: Long,
        ts: Long,
        attachments: List<MessageAttachment> = emptyList(),
        isSent: Boolean = false,
        isStarred: Boolean = false
    ) = Message(
        id = id, threadId = 1L, address = "+1", body = "", timestamp = ts, isSent = isSent,
        type = 1, isMms = attachments.isNotEmpty(), attachments = attachments, isStarred = isStarred
    )

    private fun image(uri: String) = MessageAttachment(uri, "image/jpeg")
    private fun video(uri: String) = MessageAttachment(uri, "video/mp4")
    private fun audio(uri: String) = MessageAttachment(uri, "audio/amr")

    private fun uris(refs: List<ThreadImageRef>) = refs.map { it.uri }

    @Test fun `empty message list returns empty image list`() {
        assertTrue(buildThreadImages(emptyList()).isEmpty())
    }

    @Test fun `messages with no attachments return empty image list`() {
        val messages = listOf(msg(1, 0), msg(2, 1000))
        assertTrue(buildThreadImages(messages).isEmpty())
    }

    @Test fun `single image in a single message is returned`() {
        val messages = listOf(msg(1, 0, listOf(image("content://mms/part/1"))))
        assertEquals(listOf("content://mms/part/1"), uris(buildThreadImages(messages)))
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
            uris(buildThreadImages(messages))
        )
    }

    @Test fun `video and audio attachments are excluded`() {
        val messages = listOf(
            msg(1, 0, listOf(image("content://mms/part/1"), video("content://mms/part/2"))),
            msg(2, 1000, listOf(audio("content://mms/part/3")))
        )
        assertEquals(listOf("content://mms/part/1"), uris(buildThreadImages(messages)))
    }

    @Test fun `mime type matching is case-insensitive`() {
        val messages = listOf(msg(1, 0, listOf(MessageAttachment("content://mms/part/1", "IMAGE/JPEG"))))
        assertEquals(listOf("content://mms/part/1"), uris(buildThreadImages(messages)))
    }

    @Test fun `order follows input order, not attachment id or uri`() {
        val messages = listOf(
            msg(1, 0, listOf(image("content://mms/part/99"))),
            msg(2, 1000, listOf(image("content://mms/part/1")))
        )
        assertEquals(
            listOf("content://mms/part/99", "content://mms/part/1"),
            uris(buildThreadImages(messages))
        )
    }

    @Test fun `each ref carries its owning message id`() {
        val messages = listOf(
            msg(10, 0, listOf(image("content://mms/part/1"))),
            msg(20, 1000, listOf(image("content://mms/part/2")))
        )
        val result = buildThreadImages(messages)
        assertEquals(listOf(10L, 20L), result.map { it.messageId })
    }

    @Test fun `timestamp label matches the shared friendly-timestamp formatter`() {
        val ts = 1_700_000_000_000L
        val messages = listOf(msg(1, ts, listOf(image("content://mms/part/1"))))
        val expectedLabel = formatEpochMillis(ts, FRIENDLY_TIMESTAMP_FORMATTER)
        assertEquals(expectedLabel, buildThreadImages(messages).single().timestampLabel)
    }

    @Test fun `multiple images in the same message share that message's timestamp label`() {
        val ts = 1_700_000_000_000L
        val messages = listOf(msg(1, ts, listOf(image("content://mms/part/1"), image("content://mms/part/2"))))
        val result = buildThreadImages(messages)
        assertEquals(2, result.size)
        assertEquals(result[0].timestampLabel, result[1].timestampLabel)
    }

    @Test fun `isSent is carried from the owning message`() {
        val messages = listOf(
            msg(1, 0, listOf(image("content://mms/part/1")), isSent = true),
            msg(2, 1000, listOf(image("content://mms/part/2")), isSent = false)
        )
        val result = buildThreadImages(messages)
        assertEquals(listOf(true, false), result.map { it.isSent })
    }

    @Test fun `isStarred is carried from the owning message`() {
        val messages = listOf(
            msg(1, 0, listOf(image("content://mms/part/1")), isStarred = true),
            msg(2, 1000, listOf(image("content://mms/part/2")), isStarred = false)
        )
        val result = buildThreadImages(messages)
        assertEquals(listOf(true, false), result.map { it.isStarred })
    }
}
