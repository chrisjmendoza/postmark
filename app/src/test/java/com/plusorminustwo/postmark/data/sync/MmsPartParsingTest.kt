package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.domain.model.MessageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPartParsingTest {

    private fun part(id: Long, ct: String, text: String? = null) = MmsRawPart(id, ct, text)

    @Test fun `empty parts list returns empty body and no attachments`() {
        val result = parseMmsRawParts(emptyList())
        assertEquals("", result.body)
        assertTrue(result.attachments.isEmpty())
    }

    @Test fun `single text part is returned as body`() {
        val result = parseMmsRawParts(listOf(part(1, "text/plain", "Hello")))
        assertEquals("Hello", result.body)
        assertTrue(result.attachments.isEmpty())
    }

    @Test fun `multiple text parts are concatenated`() {
        val result = parseMmsRawParts(listOf(
            part(1, "text/plain", "Hello "),
            part(2, "text/plain", "World")
        ))
        assertEquals("Hello World", result.body)
    }

    @Test fun `text parts with leading and trailing whitespace are trimmed`() {
        val result = parseMmsRawParts(listOf(part(1, "text/plain", "  hi  ")))
        assertEquals("hi", result.body)
    }

    @Test fun `smil part is skipped`() {
        val result = parseMmsRawParts(listOf(
            part(1, "application/smil", "<smil/>"),
            part(2, "text/plain", "Caption")
        ))
        assertEquals("Caption", result.body)
        assertTrue(result.attachments.isEmpty())
    }

    @Test fun `image part becomes an attachment with stable uri and mime type`() {
        val result = parseMmsRawParts(listOf(part(42, "image/jpeg")))
        assertEquals(
            listOf(MessageAttachment("content://mms/part/42", "image/jpeg")),
            result.attachments
        )
    }

    @Test fun `video part becomes an attachment`() {
        val result = parseMmsRawParts(listOf(part(7, "video/mp4")))
        assertEquals(
            listOf(MessageAttachment("content://mms/part/7", "video/mp4")),
            result.attachments
        )
    }

    @Test fun `audio part becomes an attachment`() {
        val result = parseMmsRawParts(listOf(part(3, "audio/amr")))
        assertEquals(
            listOf(MessageAttachment("content://mms/part/3", "audio/amr")),
            result.attachments
        )
    }

    @Test fun `all media parts are collected in PDU order`() {
        // Regression for the first-part-wins bug: every media part after the first
        // used to be silently dropped (MMS_AUDIT §2.2).
        val result = parseMmsRawParts(listOf(
            part(10, "image/jpeg"),
            part(11, "image/png"),
            part(12, "video/mp4")
        ))
        assertEquals(
            listOf(
                MessageAttachment("content://mms/part/10", "image/jpeg"),
                MessageAttachment("content://mms/part/11", "image/png"),
                MessageAttachment("content://mms/part/12", "video/mp4")
            ),
            result.attachments
        )
    }

    @Test fun `text and image parts coexist`() {
        val result = parseMmsRawParts(listOf(
            part(1, "image/png"),
            part(2, "text/plain", "A photo")
        ))
        assertEquals("A photo", result.body)
        assertEquals(listOf(MessageAttachment("content://mms/part/1", "image/png")), result.attachments)
    }

    @Test fun `unknown content type is ignored`() {
        val result = parseMmsRawParts(listOf(
            part(1, "application/octet-stream"),
            part(2, "text/plain", "body")
        ))
        assertEquals("body", result.body)
        assertTrue(result.attachments.isEmpty())
    }

    @Test fun `content type matching is case-insensitive`() {
        val result = parseMmsRawParts(listOf(
            part(1, "TEXT/PLAIN", "Hello"),
            part(2, "IMAGE/JPEG")
        ))
        assertEquals("Hello", result.body)
        assertEquals(listOf(MessageAttachment("content://mms/part/2", "IMAGE/JPEG")), result.attachments)
    }

    @Test fun `smil before media parts still yields text body and all attachments`() {
        val result = parseMmsRawParts(listOf(
            part(1, "application/smil"),
            part(2, "image/jpeg"),
            part(3, "text/plain", "Caption"),
            part(4, "image/gif")
        ))
        assertEquals("Caption", result.body)
        assertEquals(
            listOf(
                MessageAttachment("content://mms/part/2", "image/jpeg"),
                MessageAttachment("content://mms/part/4", "image/gif")
            ),
            result.attachments
        )
    }

    // ── previewText ─────────────────────────────────────────────────────────

    @Test fun `previewText prefers body over media label`() {
        val result = parseMmsRawParts(listOf(
            part(1, "image/jpeg"),
            part(2, "text/plain", "Look at this")
        ))
        assertEquals("Look at this", result.previewText)
    }

    @Test fun `previewText labels media-only messages by first attachment type`() {
        assertEquals("📷 Photo", parseMmsRawParts(listOf(part(1, "image/jpeg"))).previewText)
        assertEquals("🎥 Video", parseMmsRawParts(listOf(part(1, "video/mp4"))).previewText)
        assertEquals("🎵 Audio message", parseMmsRawParts(listOf(part(1, "audio/amr"))).previewText)
        assertEquals("[MMS]", parseMmsRawParts(emptyList()).previewText)
    }

    // ── parseMmsParticipants ────────────────────────────────────────────────

    @Test fun `single FROM row for an ordinary 1-1 received MMS`() {
        val result = parseMmsParticipants(listOf(MmsAddrRow("+15551234567", PDU_HEADER_FROM)))
        assertEquals(listOf("+15551234567"), result)
    }

    @Test fun `single TO row for an ordinary 1-1 sent MMS`() {
        val result = parseMmsParticipants(listOf(MmsAddrRow("+15551234567", PDU_HEADER_TO)))
        assertEquals(listOf("+15551234567"), result)
    }

    @Test fun `group MMS collects every FROM TO and CC row`() {
        // Regression for MMS_AUDIT §2.3: only the first row used to be kept.
        val result = parseMmsParticipants(listOf(
            MmsAddrRow("+15550000001", PDU_HEADER_FROM),
            MmsAddrRow("+15550000002", PDU_HEADER_TO),
            MmsAddrRow("+15550000003", PDU_HEADER_TO),
            MmsAddrRow("+15550000004", PDU_HEADER_CC)
        ))
        assertEquals(
            listOf("+15550000001", "+15550000002", "+15550000003", "+15550000004"),
            result
        )
    }

    @Test fun `duplicate addresses across rows are deduplicated`() {
        val result = parseMmsParticipants(listOf(
            MmsAddrRow("+15550000001", PDU_HEADER_FROM),
            MmsAddrRow("+15550000001", PDU_HEADER_TO)
        ))
        assertEquals(listOf("+15550000001"), result)
    }

    @Test fun `unrelated PDU header types are ignored`() {
        val result = parseMmsParticipants(listOf(
            MmsAddrRow("+15550000001", PDU_HEADER_FROM),
            MmsAddrRow("+15559999999", 999)
        ))
        assertEquals(listOf("+15550000001"), result)
    }

    @Test fun `null blank and Samsung placeholder addresses are skipped`() {
        val result = parseMmsParticipants(listOf(
            MmsAddrRow(null, PDU_HEADER_FROM),
            MmsAddrRow("", PDU_HEADER_TO),
            MmsAddrRow("insert-address-token", PDU_HEADER_TO),
            MmsAddrRow("+15550000001", PDU_HEADER_CC)
        ))
        assertEquals(listOf("+15550000001"), result)
    }

    @Test fun `empty row list returns empty participant list`() {
        assertTrue(parseMmsParticipants(emptyList()).isEmpty())
    }
}
