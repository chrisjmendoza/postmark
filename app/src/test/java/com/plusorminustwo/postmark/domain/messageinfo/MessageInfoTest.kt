package com.plusorminustwo.postmark.domain.messageinfo

import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageInfoTest {

    private fun msg(
        body: String = "hello",
        isSent: Boolean = true,
        isMms: Boolean = false,
        timestamp: Long = 1_000L,
        sentAt: Long? = null,
        deliveredAt: Long? = null,
        attachments: List<MessageAttachment> = emptyList()
    ) = Message(
        id = 1, threadId = 1, address = "+1", body = body, timestamp = timestamp,
        isSent = isSent, type = if (isSent) 2 else 1, isMms = isMms,
        attachments = attachments, sentAt = sentAt, deliveredAt = deliveredAt
    )

    private fun rowLabels(m: Message) = messageInfoRows(m).map { it.label }
    private fun valueOf(m: Message, label: String) =
        messageInfoRows(m).first { it.label == label }.value

    @Test
    fun `sent message uses sentAt for the Sent row when present`() {
        val v = valueOf(msg(isSent = true, timestamp = 1_000L, sentAt = 500L), "Sent")
        assertEquals(MessageInfoValue.Timestamp(500L), v)
    }

    @Test
    fun `sent message without sentAt falls back to timestamp`() {
        val v = valueOf(msg(isSent = true, timestamp = 1_000L, sentAt = null), "Sent")
        assertEquals(MessageInfoValue.Timestamp(1_000L), v)
    }

    @Test
    fun `received message shows a Received row keyed on timestamp`() {
        val labels = rowLabels(msg(isSent = false, timestamp = 2_000L))
        assertTrue("Received" in labels)
        assertTrue("Sent" !in labels)
        assertEquals(MessageInfoValue.Timestamp(2_000L), valueOf(msg(isSent = false, timestamp = 2_000L), "Received"))
    }

    @Test
    fun `delivered row appears only when deliveredAt is set`() {
        assertTrue("Delivered" !in rowLabels(msg(deliveredAt = null)))
        val labels = rowLabels(msg(deliveredAt = 3_000L))
        assertTrue("Delivered" in labels)
        assertEquals(MessageInfoValue.Timestamp(3_000L), valueOf(msg(deliveredAt = 3_000L), "Delivered"))
    }

    @Test
    fun `characters row reflects body length and is omitted for blank bodies`() {
        assertEquals(MessageInfoValue.Text("5"), valueOf(msg(body = "hello"), "Characters"))
        assertTrue("Characters" !in rowLabels(msg(body = "   ")))
        assertTrue("Characters" !in rowLabels(msg(body = "")))
    }

    @Test
    fun `sms row set has Type SMS and Parts, no Attachments`() {
        val rows = messageInfoRows(msg(isMms = false, body = "hi"))
        assertEquals(MessageInfoValue.Text("SMS"), rows.first { it.label == "Type" }.value)
        assertTrue(rows.any { it.value is MessageInfoValue.SmsParts })
        assertTrue(rows.none { it.label == "Attachments" })
    }

    @Test
    fun `mms row set has Type MMS and attachment count, no Parts`() {
        val m = msg(
            isMms = true,
            attachments = listOf(
                MessageAttachment("content://mms/part/1", "image/jpeg"),
                MessageAttachment("content://mms/part/2", "image/png")
            )
        )
        val rows = messageInfoRows(m)
        assertEquals(MessageInfoValue.Text("MMS"), rows.first { it.label == "Type" }.value)
        assertEquals(MessageInfoValue.Text("2"), rows.first { it.label == "Attachments" }.value)
        assertTrue(rows.none { it.value is MessageInfoValue.SmsParts })
    }

    @Test
    fun `mms without attachments omits the Attachments row`() {
        assertTrue("Attachments" !in rowLabels(msg(isMms = true, attachments = emptyList())))
    }
}
