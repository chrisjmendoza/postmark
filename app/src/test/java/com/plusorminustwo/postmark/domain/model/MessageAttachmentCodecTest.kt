package com.plusorminustwo.postmark.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the JSON codec that persists List<MessageAttachment> in the
 * messages.attachmentsJson column. The decoder only has to understand what
 * the encoder emits, so every test is a round-trip plus a few malformed inputs.
 */
class MessageAttachmentCodecTest {

    @Test fun `empty list encodes to null`() {
        assertNull(encodeAttachmentsJson(emptyList()))
    }

    @Test fun `null and blank decode to empty list`() {
        assertTrue(decodeAttachmentsJson(null).isEmpty())
        assertTrue(decodeAttachmentsJson("").isEmpty())
        assertTrue(decodeAttachmentsJson("   ").isEmpty())
    }

    @Test fun `single attachment round-trips`() {
        val list = listOf(MessageAttachment("content://mms/part/42", "image/jpeg"))
        assertEquals(list, decodeAttachmentsJson(encodeAttachmentsJson(list)))
    }

    @Test fun `multiple attachments round-trip preserving order`() {
        val list = listOf(
            MessageAttachment("content://mms/part/1", "image/jpeg"),
            MessageAttachment("content://mms/part/2", "video/mp4"),
            MessageAttachment("content://media/picker/0/com.android.providers.media.photopicker/media/3", "image/png")
        )
        assertEquals(list, decodeAttachmentsJson(encodeAttachmentsJson(list)))
    }

    @Test fun `encoded form is a json array of uri-mimeType objects`() {
        val json = encodeAttachmentsJson(listOf(MessageAttachment("content://x/1", "image/gif")))
        assertEquals("""[{"uri":"content://x/1","mimeType":"image/gif"}]""", json)
    }

    @Test fun `quotes and backslashes in uri survive the round-trip`() {
        val list = listOf(MessageAttachment("""content://weird/"quoted"\path""", "image/jpeg"))
        assertEquals(list, decodeAttachmentsJson(encodeAttachmentsJson(list)))
    }

    @Test fun `unicode in values survives the round-trip`() {
        val list = listOf(MessageAttachment("content://mms/part/9?名前=写真", "image/jpeg"))
        assertEquals(list, decodeAttachmentsJson(encodeAttachmentsJson(list)))
    }

    @Test fun `garbage input decodes to empty list instead of throwing`() {
        assertTrue(decodeAttachmentsJson("not json at all").isEmpty())
        assertTrue(decodeAttachmentsJson("[{\"uri\":\"only-half").isEmpty())
        assertTrue(decodeAttachmentsJson("[]").isEmpty())
    }
}
