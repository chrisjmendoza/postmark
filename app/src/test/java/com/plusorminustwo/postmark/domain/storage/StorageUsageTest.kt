package com.plusorminustwo.postmark.domain.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the two pure Storage usage screen functions: filename→thread attachment
 *  attribution and top-N conversation breakdown assembly. */
class StorageUsageTest {

    // ── attributeAttachmentBytes ────────────────────────────────────────────────

    @Test
    fun `attributes each file's bytes to its referencing thread`() {
        val files = listOf(
            "mms_attach_1.bin" to 1000L,
            "mms_attach_2.bin" to 2000L
        )
        val uriToThread = mapOf(
            "mms_attach_1.bin" to 10L,
            "mms_attach_2.bin" to 20L
        )
        val result = attributeAttachmentBytes(files, uriToThread)
        assertEquals(mapOf(10L to 1000L, 20L to 2000L), result)
    }

    @Test
    fun `sums multiple files attributed to the same thread`() {
        val files = listOf(
            "mms_attach_1.bin" to 1000L,
            "mms_attach_1_1.bin" to 500L,
            "voice_memo_123.m4a" to 300L
        )
        val uriToThread = mapOf(
            "mms_attach_1.bin" to 10L,
            "mms_attach_1_1.bin" to 10L,
            "voice_memo_123.m4a" to 10L
        )
        val result = attributeAttachmentBytes(files, uriToThread)
        assertEquals(mapOf(10L to 1800L), result)
    }

    @Test
    fun `a file absent from the referenced map contributes to no thread`() {
        // Orphaned leftover, or a voice memo recorded but not yet sent — neither
        // belongs to any thread yet.
        val files = listOf(
            "mms_attach_1.bin" to 1000L,
            "voice_memo_999.m4a" to 400L // unreferenced: pending unsent memo
        )
        val uriToThread = mapOf("mms_attach_1.bin" to 10L)
        val result = attributeAttachmentBytes(files, uriToThread)
        assertEquals(mapOf(10L to 1000L), result)
    }

    @Test
    fun `empty file list yields an empty map`() {
        assertTrue(attributeAttachmentBytes(emptyList(), mapOf("x" to 1L)).isEmpty())
    }

    // ── buildConversationBreakdown ──────────────────────────────────────────────

    @Test
    fun `orders by message count descending`() {
        val counts = mapOf(1L to 5, 2L to 50, 3L to 20)
        val names = mapOf(1L to "Alice", 2L to "Bob", 3L to "Carol")
        val result = buildConversationBreakdown(counts, names, emptyMap())
        assertEquals(listOf(2L, 3L, 1L), result.map { it.threadId })
    }

    @Test
    fun `ties broken by ascending threadId for deterministic ordering`() {
        val counts = mapOf(5L to 10, 3L to 10, 4L to 10)
        val result = buildConversationBreakdown(counts, emptyMap(), emptyMap())
        assertEquals(listOf(3L, 4L, 5L), result.map { it.threadId })
    }

    @Test
    fun `respects the limit parameter`() {
        val counts = (1L..30L).associateWith { it.toInt() }
        val result = buildConversationBreakdown(counts, emptyMap(), emptyMap(), limit = 20)
        assertEquals(20, result.size)
        // Highest counts (30..11) win under the limit.
        assertEquals(30L, result.first().threadId)
        assertEquals(11L, result.last().threadId)
    }

    @Test
    fun `falls back to Unknown when a thread name is missing`() {
        val result = buildConversationBreakdown(mapOf(1L to 3), emptyMap(), emptyMap())
        assertEquals("Unknown", result.single().name)
    }

    @Test
    fun `attaches attachment bytes per thread, defaulting to zero`() {
        val counts = mapOf(1L to 3, 2L to 1)
        val names = mapOf(1L to "Alice", 2L to "Bob")
        val bytes = mapOf(1L to 5000L)
        val result = buildConversationBreakdown(counts, names, bytes)
        assertEquals(5000L, result.first { it.threadId == 1L }.attachmentBytes)
        assertEquals(0L, result.first { it.threadId == 2L }.attachmentBytes)
    }

    @Test
    fun `empty counts yields an empty breakdown`() {
        assertTrue(buildConversationBreakdown(emptyMap(), emptyMap(), emptyMap()).isEmpty())
    }
}
