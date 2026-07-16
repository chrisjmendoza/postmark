package com.plusorminustwo.postmark.domain.formatter

import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.Reaction
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ExportFormatterTest {

    private val ownAddress = "+15550001111"
    private val theirAddress = "+15559998888"
    private val displayName = "Sarah"

    // Fixed timestamps (UTC) — 2024-04-14 10:03 AM and 10:07 AM same day
    private val t1 = 1713085380000L // 2024-04-14 10:03 AM UTC
    private val t2 = 1713085620000L // 2024-04-14 10:07 AM UTC
    // A message the next day: 2024-04-15
    private val t3 = 1713171780000L // 2024-04-15 10:03 AM UTC

    @Test
    fun `empty list returns empty string`() {
        val result = ExportFormatter.formatForCopy(emptyList(), displayName, ownAddress)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `header always shows conversation name`() {
        val msg = received(t1, "Hey")
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, ownAddress)
        assertTrue(result.startsWith("Conversation with $displayName"))
    }

    @Test
    fun `header includes formatted phone number after the name`() {
        val msg = received(t1, "Hey")
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, ownAddress, theirAddress)
        assertTrue(result.startsWith("Conversation with $displayName (555) 999-8888"))
    }

    @Test
    fun `header does not repeat the number for unknown contacts`() {
        // Unknown contact: displayName IS the raw address — number must appear once.
        val msg = received(t1, "Hey")
        val result = ExportFormatter.formatForCopy(listOf(msg), theirAddress, ownAddress, theirAddress)
        assertTrue(result.startsWith("Conversation with $theirAddress\n"))
    }

    @Test
    fun `header does not repeat the number when the display name is already formatted`() {
        val formatted = formatPhoneNumber(theirAddress)
        val msg = received(t1, "Hey")
        val result = ExportFormatter.formatForCopy(listOf(msg), formatted, ownAddress, theirAddress)
        assertTrue(result.startsWith("Conversation with $formatted\n"))
    }

    @Test
    fun `attachment note is appended after the body`() {
        val msg = received(t1, "look at this")
        val result = ExportFormatter.formatForCopy(
            listOf(msg), displayName, ownAddress, theirAddress
        ) { "[Attachment: media/Sarah/2024-04-14_1003.jpg]" }
        assertTrue(result.contains("look at this\n[Attachment: media/Sarah/2024-04-14_1003.jpg]"))
    }

    @Test
    fun `photo-only message exports the note instead of a blank line`() {
        val msg = received(t1, "")
        val result = ExportFormatter.formatForCopy(
            listOf(msg), displayName, ownAddress, theirAddress
        ) { "[Attachment: media/Sarah/2024-04-14_1003.jpg]" }
        assertTrue(result.contains("[Attachment: media/Sarah/2024-04-14_1003.jpg]"))
        assertFalse("Blank body line should be dropped when a note is present", result.contains("\n\n\n"))
    }

    @Test
    fun `messages without attachments are unaffected by the note lambda`() {
        val withNote = ExportFormatter.formatForCopy(
            listOf(received(t1, "plain")), displayName, ownAddress, theirAddress
        ) { null }
        val without = ExportFormatter.formatForCopy(
            listOf(received(t1, "plain")), displayName, ownAddress, theirAddress
        )
        assertEquals(without, withNote)
    }

    @Test
    fun `header omits number when no address is supplied`() {
        val msg = received(t1, "Hey")
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, ownAddress)
        assertTrue(result.startsWith("Conversation with $displayName\n"))
    }

    @Test
    fun `single-day selection has no date divider`() {
        val msgs = listOf(received(t1, "Hey"), sent(t2, "Hi back"))
        val result = ExportFormatter.formatForCopy(msgs, displayName, ownAddress)
        assertFalse("Single-day export should not include a date divider", result.contains("────"))
    }

    @Test
    fun `single-day selection shows date once`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val msgs = listOf(received(t1, "Hey"), sent(t2, "Hi back"))
        val result = ExportFormatter.formatForCopy(msgs, displayName, ownAddress)
        val count = result.split("April 14, 2024").size - 1
        assertEquals("Single-day export should include the date exactly once", 1, count)
    }

    @Test
    fun `multi-day selection includes date divider`() {
        val msgs = listOf(received(t1, "Day one"), received(t3, "Day two"))
        val result = ExportFormatter.formatForCopy(msgs, displayName, ownAddress)
        assertTrue("Multi-day export must include date divider", result.contains("────"))
    }

    @Test
    fun `sent message labeled You`() {
        val msg = sent(t1, "I sent this")
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, ownAddress)
        assertTrue(result.contains("You ("))
        assertFalse(result.contains("$displayName ("))
    }

    @Test
    fun `received message labeled with display name`() {
        val msg = received(t1, "They sent this")
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, ownAddress)
        assertTrue(result.contains("$displayName ("))
        assertFalse(result.contains("You ("))
    }

    @Test
    fun `reaction line appended below message`() {
        val reaction = Reaction(
            id = 1, messageId = 1, senderAddress = theirAddress,
            emoji = "❤️", timestamp = t1, rawText = "Loved 'Hi back'"
        )
        val msg = sent(t1, "Hi back").copy(reactions = listOf(reaction))
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, ownAddress)
        // Format: "  ↩ ❤️ Sarah" (grouped-per-message style introduced in 9d5ff2d)
        assertTrue(result.contains("↩ ❤️ $displayName"))
    }

    @Test
    fun `own reaction labeled You`() {
        val reaction = Reaction(
            id = 2, messageId = 2, senderAddress = ownAddress,
            emoji = "😂", timestamp = t1, rawText = "Laughed at 'something'"
        )
        val msg = received(t1, "something").copy(reactions = listOf(reaction))
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, ownAddress)
        assertTrue(result.contains("↩ 😂 You"))
    }

    @Test
    fun `message body appears in output`() {
        val body = "Did you end up watching that show?"
        val result = ExportFormatter.formatForCopy(listOf(received(t1, body)), displayName, ownAddress)
        assertTrue(result.contains(body))
    }

    // ── Media placeholders ────────────────────────────────────────────────────

    @Test
    fun `mediaPlaceholder is null for a plain text message`() {
        assertEquals(null, ExportFormatter.mediaPlaceholder(received(t1, "hi")))
    }

    @Test
    fun `mediaPlaceholder labels a single photo`() {
        val msg = received(t1, "").copy(attachments = listOf(image()))
        assertEquals("[Photo]", ExportFormatter.mediaPlaceholder(msg))
    }

    @Test
    fun `mediaPlaceholder counts same-kind attachments`() {
        val msg = received(t1, "").copy(attachments = listOf(image(), image("content://b")))
        assertEquals("[2 photos]", ExportFormatter.mediaPlaceholder(msg))
    }

    @Test
    fun `mediaPlaceholder describes mixed media kinds`() {
        val msg = received(t1, "").copy(
            attachments = listOf(image(), MessageAttachment("content://v", "video/mp4"))
        )
        assertEquals("[Photo, video]", ExportFormatter.mediaPlaceholder(msg))
    }

    @Test
    fun `photo-only message copies as placeholder line, not a blank`() {
        val msg = received(t1, "").copy(attachments = listOf(image()))
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, ownAddress)
        assertTrue(result.contains("[Photo]"))
        // The sender line must be directly followed by the placeholder — no blank body line.
        assertTrue(result.lines().none { it.isEmpty() && result.lines().indexOf(it) == 2 })
    }

    @Test
    fun `caption and placeholder both appear for media with body text`() {
        val msg = received(t1, "look at this").copy(attachments = listOf(image()))
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, ownAddress)
        assertTrue(result.contains("look at this"))
        assertTrue(result.contains("[Photo]"))
    }

    private fun image(uri: String = "content://a") = MessageAttachment(uri, "image/jpeg")

    // Helpers

    private fun sent(ts: Long, body: String) = Message(
        id = ts, threadId = 1, address = ownAddress,
        body = body, timestamp = ts, isSent = true, type = 2
    )

    private fun received(ts: Long, body: String) = Message(
        id = ts, threadId = 1, address = theirAddress,
        body = body, timestamp = ts, isSent = false, type = 1
    )
}
