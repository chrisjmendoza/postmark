package com.plusorminustwo.postmark.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MmsPartParsingTest {

    private fun part(id: Long, ct: String, text: String? = null) = MmsRawPart(id, ct, text)

    @Test fun `empty parts list returns empty body and no attachment`() {
        val result = parseMmsRawParts(emptyList())
        assertEquals("", result.body)
        assertNull(result.attachmentUri)
        assertNull(result.mimeType)
    }

    @Test fun `single text part is returned as body`() {
        val result = parseMmsRawParts(listOf(part(1, "text/plain", "Hello")))
        assertEquals("Hello", result.body)
        assertNull(result.attachmentUri)
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
        assertNull(result.attachmentUri)
    }

    @Test fun `image part sets attachment uri and mime type`() {
        val result = parseMmsRawParts(listOf(part(42, "image/jpeg")))
        assertEquals("content://mms/part/42", result.attachmentUri)
        assertEquals("image/jpeg", result.mimeType)
    }

    @Test fun `video part sets attachment uri`() {
        val result = parseMmsRawParts(listOf(part(7, "video/mp4")))
        assertEquals("content://mms/part/7", result.attachmentUri)
        assertEquals("video/mp4", result.mimeType)
    }

    @Test fun `audio part sets attachment uri`() {
        val result = parseMmsRawParts(listOf(part(3, "audio/amr")))
        assertEquals("content://mms/part/3", result.attachmentUri)
        assertEquals("audio/amr", result.mimeType)
    }

    @Test fun `first media part wins, second is ignored`() {
        val result = parseMmsRawParts(listOf(
            part(10, "image/jpeg"),
            part(11, "image/png")
        ))
        assertEquals("content://mms/part/10", result.attachmentUri)
        assertEquals("image/jpeg", result.mimeType)
    }

    @Test fun `text and image parts coexist`() {
        val result = parseMmsRawParts(listOf(
            part(1, "image/png"),
            part(2, "text/plain", "A photo")
        ))
        assertEquals("A photo", result.body)
        assertEquals("content://mms/part/1", result.attachmentUri)
        assertEquals("image/png", result.mimeType)
    }

    @Test fun `unknown content type is ignored`() {
        val result = parseMmsRawParts(listOf(
            part(1, "application/octet-stream"),
            part(2, "text/plain", "body")
        ))
        assertEquals("body", result.body)
        assertNull(result.attachmentUri)
    }

    @Test fun `content type matching is case-insensitive`() {
        val result = parseMmsRawParts(listOf(
            part(1, "TEXT/PLAIN", "Hello"),
            part(2, "IMAGE/JPEG")
        ))
        assertEquals("Hello", result.body)
        assertEquals("content://mms/part/2", result.attachmentUri)
    }

    @Test fun `smil before text part still yields text body`() {
        val result = parseMmsRawParts(listOf(
            part(1, "application/smil"),
            part(2, "image/jpeg"),
            part(3, "text/plain", "Caption")
        ))
        assertEquals("Caption", result.body)
        assertEquals("content://mms/part/2", result.attachmentUri)
    }
}
