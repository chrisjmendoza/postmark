package com.plusorminustwo.postmark.domain.formatter

import com.plusorminustwo.postmark.domain.customization.CopyFormatOptions
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ExportFormatterTest {

    private val ownAddress = "+15550001111"
    private val theirAddress = "+15559998888"
    private val displayName = "Sarah"

    // Fixed timestamps (UTC) — 2024-04-14 9:03 AM and 9:07 AM same day
    private val t1 = 1713085380000L // 2024-04-14 9:03 AM UTC
    private val t2 = 1713085620000L // 2024-04-14 9:07 AM UTC
    // A message the next day: 2024-04-15
    private val t3 = 1713171780000L // 2024-04-15 9:03 AM UTC

    @Test
    fun `empty list returns empty string`() {
        val result = ExportFormatter.formatForCopy(emptyList(), displayName)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `header always shows conversation name`() {
        val msg = received(t1, "Hey")
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName)
        assertTrue(result.startsWith("Conversation with $displayName"))
    }

    @Test
    fun `header includes formatted phone number after the name`() {
        val msg = received(t1, "Hey")
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, theirAddress)
        assertTrue(result.startsWith("Conversation with $displayName (555) 999-8888"))
    }

    @Test
    fun `header omits number when no address is supplied`() {
        val msg = received(t1, "Hey")
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName)
        assertTrue(result.startsWith("Conversation with $displayName\n"))
    }

    /** The whole shape of a copied transcript, pinned end to end. */
    @Test
    fun `full transcript layout`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val msgs = listOf(
            received(t1, "Hey"),
            sent(t2, "Hi back").copy(reactions = listOf(theirReaction("❤️")))
        )
        assertEquals(
            """
            Conversation with Sarah (555) 999-8888
            April 14, 2024
            Sarah (9:03 AM)
            Hey

            You (9:07 AM)
            Hi back
              ↩ Sarah reacted ❤️
            """.trimIndent(),
            ExportFormatter.formatForCopy(msgs, displayName, theirAddress)
        )
    }

    // ── The number belongs in the header and nowhere else ─────────────────────

    @Test
    fun `number appears exactly once for a named contact`() {
        val msgs = listOf(received(t1, "Hey"), sent(t2, "Hi back"))
        val result = ExportFormatter.formatForCopy(msgs, displayName, theirAddress)
        assertEquals(1, occurrences(result, "(555) 999-8888"))
    }

    @Test
    fun `unknown contact shows the number in the header only`() {
        // Unknown contact: the display name IS the raw address. Labelling every
        // incoming line with it repeated the number down the whole transcript.
        val msgs = listOf(received(t1, "Hey"), received(t2, "You there?"))
        val result = ExportFormatter.formatForCopy(msgs, theirAddress, theirAddress)
        assertTrue(result.startsWith("Conversation with (555) 999-8888\n"))
        assertEquals(1, occurrences(result, "(555) 999-8888"))
        assertEquals(0, occurrences(result, theirAddress))
    }

    @Test
    fun `unknown contact labels received messages Them`() {
        val result = ExportFormatter.formatForCopy(
            listOf(received(t1, "Hey"), sent(t2, "Hi")), theirAddress, theirAddress
        )
        assertTrue(result.contains("${ExportFormatter.UNNAMED_SENDER} ("))
        assertTrue(result.contains("You ("))
    }

    @Test
    fun `already formatted display name is still treated as a number`() {
        val formatted = formatPhoneNumber(theirAddress)
        val result = ExportFormatter.formatForCopy(listOf(received(t1, "Hey")), formatted, theirAddress)
        assertTrue(result.startsWith("Conversation with $formatted\n"))
        assertEquals(1, occurrences(result, formatted))
    }

    @Test
    fun `blank display name and address still produce a header`() {
        val result = ExportFormatter.formatForCopy(listOf(received(t1, "Hey")), "")
        assertTrue(result.startsWith("Conversation\n"))
    }

    // ── Options ───────────────────────────────────────────────────────────────

    @Test
    fun `phone number omitted when the option is off`() {
        val result = ExportFormatter.formatForCopy(
            listOf(received(t1, "Hey")), displayName, theirAddress,
            CopyFormatOptions(includePhoneNumber = false)
        )
        assertTrue(result.startsWith("Conversation with $displayName\n"))
        assertEquals(0, occurrences(result, "555"))
    }

    @Test
    fun `unknown contact header drops the number when the option is off`() {
        val result = ExportFormatter.formatForCopy(
            listOf(received(t1, "Hey")), theirAddress, theirAddress,
            CopyFormatOptions(includePhoneNumber = false)
        )
        assertTrue(result.startsWith("Conversation\n"))
        assertEquals(0, occurrences(result, "555"))
    }

    @Test
    fun `dates omitted when the option is off`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val msgs = listOf(received(t1, "Day one"), received(t3, "Day two"))
        val result = ExportFormatter.formatForCopy(
            msgs, displayName, theirAddress, CopyFormatOptions(includeDates = false)
        )
        assertFalse(result.contains("April 14, 2024"))
        assertFalse(result.contains("────"))
    }

    @Test
    fun `timestamps omitted when the option is off`() {
        val result = ExportFormatter.formatForCopy(
            listOf(received(t1, "Hey")), displayName, theirAddress,
            CopyFormatOptions(includeTimestamps = false)
        )
        assertTrue(result.contains("\n$displayName\nHey"))
    }

    @Test
    fun `reactions omitted when the option is off`() {
        val msg = sent(t1, "Hi back").copy(reactions = listOf(theirReaction("❤️")))
        val result = ExportFormatter.formatForCopy(
            listOf(msg), displayName, theirAddress, CopyFormatOptions(includeReactions = false)
        )
        assertFalse(result.contains("↩"))
    }

    @Test
    fun `attachments omitted when the option is off`() {
        val msg = received(t1, "look").copy(attachments = listOf(image()))
        val result = ExportFormatter.formatForCopy(
            listOf(msg), displayName, theirAddress, CopyFormatOptions(includeAttachments = false)
        )
        assertFalse(result.contains("[Photo]"))
        assertTrue(result.contains("look"))
    }

    // ── Attachments ───────────────────────────────────────────────────────────

    @Test
    fun `attachment note is appended after the body`() {
        val msg = received(t1, "look at this")
        val result = ExportFormatter.formatForCopy(
            listOf(msg), displayName, theirAddress
        ) { "[Attachment: media/Sarah/2024-04-14_0903.jpg]" }
        assertTrue(result.contains("look at this\n[Attachment: media/Sarah/2024-04-14_0903.jpg]"))
    }

    @Test
    fun `photo-only message exports the note instead of a blank line`() {
        val msg = received(t1, "")
        val result = ExportFormatter.formatForCopy(
            listOf(msg), displayName, theirAddress
        ) { "[Attachment: media/Sarah/2024-04-14_0903.jpg]" }
        assertTrue(result.contains("[Attachment: media/Sarah/2024-04-14_0903.jpg]"))
        assertFalse("Blank body line should be dropped when a note is present", result.contains("\n\n\n"))
    }

    @Test
    fun `messages without attachments are unaffected by the note lambda`() {
        val withNote = ExportFormatter.formatForCopy(
            listOf(received(t1, "plain")), displayName, theirAddress
        ) { null }
        val without = ExportFormatter.formatForCopy(
            listOf(received(t1, "plain")), displayName, theirAddress
        )
        assertEquals(without, withNote)
    }

    // ── Days ──────────────────────────────────────────────────────────────────

    @Test
    fun `single-day selection has no date divider`() {
        val msgs = listOf(received(t1, "Hey"), sent(t2, "Hi back"))
        val result = ExportFormatter.formatForCopy(msgs, displayName)
        assertFalse("Single-day export should not include a date divider", result.contains("────"))
    }

    @Test
    fun `single-day selection shows date once`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val msgs = listOf(received(t1, "Hey"), sent(t2, "Hi back"))
        val result = ExportFormatter.formatForCopy(msgs, displayName)
        assertEquals(
            "Single-day export should include the date exactly once",
            1, occurrences(result, "April 14, 2024")
        )
    }

    @Test
    fun `multi-day selection includes date divider`() {
        val msgs = listOf(received(t1, "Day one"), received(t3, "Day two"))
        val result = ExportFormatter.formatForCopy(msgs, displayName)
        assertTrue("Multi-day export must include date divider", result.contains("────"))
    }

    // ── Sender labels ─────────────────────────────────────────────────────────

    @Test
    fun `sent message labeled You`() {
        val msg = sent(t1, "I sent this")
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName)
        assertTrue(result.contains("You ("))
        assertFalse(result.contains("$displayName ("))
    }

    @Test
    fun `received message labeled with display name`() {
        val msg = received(t1, "They sent this")
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName)
        assertTrue(result.contains("$displayName ("))
        assertFalse(result.contains("You ("))
    }

    @Test
    fun `message body appears in output`() {
        val body = "Did you end up watching that show?"
        val result = ExportFormatter.formatForCopy(listOf(received(t1, body)), displayName)
        assertTrue(result.contains(body))
    }

    // ── Reactions ─────────────────────────────────────────────────────────────

    @Test
    fun `their reaction on my message names them`() {
        val msg = sent(t1, "Hi back").copy(reactions = listOf(theirReaction("❤️")))
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, theirAddress)
        assertTrue(result.contains("↩ $displayName reacted ❤️"))
        assertFalse(result.contains("You reacted"))
    }

    @Test
    fun `my reaction on their message names me`() {
        // Reactions the local user adds are stored under SELF_ADDRESS — this is the
        // case that used to read as though the contact had sent the reaction.
        val msg = received(t1, "something").copy(reactions = listOf(myReaction("❤️")))
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, theirAddress)
        assertTrue(result.contains("↩ You reacted ❤️"))
        assertFalse(result.contains("$displayName reacted"))
    }

    @Test
    fun `reaction line sits directly under the message reacted to`() {
        val reacted = sent(t1, "Hi back").copy(reactions = listOf(myReaction("❤️")))
        val later = received(t2, "later message")
        val lines = ExportFormatter
            .formatForCopy(listOf(reacted, later), displayName, theirAddress)
            .lines()
        val bodyIndex = lines.indexOf("Hi back")
        assertTrue(bodyIndex > 0)
        assertEquals("  ↩ You reacted ❤️", lines[bodyIndex + 1])
    }

    @Test
    fun `both parties reacting are named separately`() {
        val msg = sent(t1, "Hi back")
            .copy(reactions = listOf(myReaction("❤️"), theirReaction("😂")))
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, theirAddress)
        assertTrue(result.contains("↩ You reacted ❤️, $displayName reacted 😂"))
    }

    @Test
    fun `multiple emoji from one reactor are listed together`() {
        val msg = sent(t1, "Hi back")
            .copy(reactions = listOf(theirReaction("❤️"), theirReaction("😂", id = 9)))
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName, theirAddress)
        assertTrue(result.contains("↩ $displayName reacted ❤️ 😂"))
    }

    @Test
    fun `unknown contact reaction uses the Them label, not the number`() {
        val msg = sent(t1, "Hi back").copy(reactions = listOf(theirReaction("❤️")))
        val result = ExportFormatter.formatForCopy(listOf(msg), theirAddress, theirAddress)
        assertTrue(result.contains("↩ ${ExportFormatter.UNNAMED_SENDER} reacted ❤️"))
        assertEquals(1, occurrences(result, "(555) 999-8888"))
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
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName)
        // Header, date, sender line, then the placeholder — no blank body line between.
        assertEquals("[Photo]", result.lines()[3])
    }

    @Test
    fun `caption and placeholder both appear for media with body text`() {
        val msg = received(t1, "look at this").copy(attachments = listOf(image()))
        val result = ExportFormatter.formatForCopy(listOf(msg), displayName)
        assertTrue(result.contains("look at this"))
        assertTrue(result.contains("[Photo]"))
    }

    // Helpers

    private fun occurrences(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun image(uri: String = "content://a") = MessageAttachment(uri, "image/jpeg")

    private fun myReaction(emoji: String, id: Long = 1) = Reaction(
        id = id, messageId = 1, senderAddress = SELF_ADDRESS,
        emoji = emoji, timestamp = t1, rawText = ""
    )

    private fun theirReaction(emoji: String, id: Long = 2) = Reaction(
        id = id, messageId = 1, senderAddress = theirAddress,
        emoji = emoji, timestamp = t1, rawText = "Loved it"
    )

    private fun sent(ts: Long, body: String) = Message(
        id = ts, threadId = 1, address = ownAddress,
        body = body, timestamp = ts, isSent = true, type = 2
    )

    private fun received(ts: Long, body: String) = Message(
        id = ts, threadId = 1, address = theirAddress,
        body = body, timestamp = ts, isSent = false, type = 1
    )
}
