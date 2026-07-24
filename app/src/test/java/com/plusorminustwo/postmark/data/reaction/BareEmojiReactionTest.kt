package com.plusorminustwo.postmark.data.reaction

import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for bare-emoji MMS reaction detection & target resolution (BareEmojiReaction.kt).
 *
 * Background: a reaction to a media message over RCS can archive into the telephony MMS
 * store as a message whose whole body is a lone emoji, with none of the `❤️ to "…"` quoted
 * structure the other strategies rely on. These pure functions decide which such rows are
 * reactions (immediately preceded by media in a 1:1 thread) versus genuine one-word replies.
 */
class BareEmojiReactionTest {

    // ── grapheme detection ─────────────────────────────────────────────────────

    @Test fun `heart with variation selector is one grapheme`() {
        assertTrue(isSingleEmojiGrapheme("❤️"))
    }

    @Test fun `simple thumbs up is one grapheme`() {
        assertTrue(isSingleEmojiGrapheme("👍"))
    }

    @Test fun `zwj family sequence is one grapheme`() {
        assertTrue(isSingleEmojiGrapheme("👨‍👩‍👧‍👦"))
    }

    @Test fun `skin-tone modified emoji is one grapheme`() {
        assertTrue(isSingleEmojiGrapheme("👍🏽"))
    }

    @Test fun `leading and trailing whitespace is trimmed`() {
        assertTrue(isSingleEmojiGrapheme("  ❤️ "))
    }

    @Test fun `two hearts is rejected`() {
        assertFalse(isSingleEmojiGrapheme("❤️❤️"))
    }

    @Test fun `text before emoji is rejected`() {
        assertFalse(isSingleEmojiGrapheme("ok 👍"))
    }

    @Test fun `emoji followed by punctuation is rejected`() {
        assertFalse(isSingleEmojiGrapheme("👍!"))
    }

    @Test fun `plain ascii letter is rejected`() {
        assertFalse(isSingleEmojiGrapheme("k"))
    }

    @Test fun `empty string is rejected`() {
        assertFalse(isSingleEmojiGrapheme(""))
    }

    @Test fun `blank string is rejected`() {
        assertFalse(isSingleEmojiGrapheme("   "))
    }

    // ── candidate detection ────────────────────────────────────────────────────

    @Test fun `lone-emoji mms in a 1to1 thread is a candidate`() {
        assertTrue(isBareEmojiReactionCandidate(mms(1, "❤️"), participantCount = 0))
    }

    @Test fun `lone-emoji sms is never a candidate`() {
        // SMS is genuine content — a typed emoji text must stay a message.
        assertFalse(isBareEmojiReactionCandidate(sms(1, "❤️"), participantCount = 0))
    }

    @Test fun `mms with an attachment is not a candidate`() {
        assertFalse(isBareEmojiReactionCandidate(mms(1, "❤️", withAttachment = true), participantCount = 0))
    }

    @Test fun `lone-emoji mms in a group thread is not a candidate`() {
        assertFalse(isBareEmojiReactionCandidate(mms(1, "❤️"), participantCount = 2))
    }

    @Test fun `multi-character mms body is not a candidate`() {
        assertFalse(isBareEmojiReactionCandidate(mms(1, "haha nice"), participantCount = 0))
    }

    // ── target resolution ──────────────────────────────────────────────────────

    private val notFallback: (Message) -> Boolean = { false }

    @Test fun `attaches to an immediately-preceding media message`() {
        val photo = mms(10, "", ts = 1_000, withAttachment = true)
        val heart = mms(11, "❤️", ts = 2_000)
        val target = findBareEmojiReactionTarget(heart, listOf(photo, heart), 0, notFallback)
        assertEquals(10L, target?.id)
    }

    @Test fun `does not attach when the preceding message is plain text`() {
        val text = mms(10, "sounds good", ts = 1_000)
        val heart = mms(11, "❤️", ts = 2_000)
        assertNull(findBareEmojiReactionTarget(heart, listOf(text, heart), 0, notFallback))
    }

    @Test fun `does not attach when nothing precedes the candidate`() {
        val heart = mms(11, "❤️", ts = 2_000)
        assertNull(findBareEmojiReactionTarget(heart, listOf(heart), 0, notFallback))
    }

    @Test fun `received candidate resolves against preceding media`() {
        val photo = mms(10, "", ts = 1_000, withAttachment = true, isSent = false)
        val heart = mms(11, "❤️", ts = 2_000, isSent = false)
        assertEquals(10L, findBareEmojiReactionTarget(heart, listOf(photo, heart), 0, notFallback)?.id)
    }

    @Test fun `sent candidate resolves against preceding media`() {
        val photo = mms(10, "", ts = 1_000, withAttachment = true, isSent = true)
        val heart = mms(11, "❤️", ts = 2_000, isSent = true)
        assertEquals(10L, findBareEmojiReactionTarget(heart, listOf(photo, heart), 0, notFallback)?.id)
    }

    @Test fun `candidate is excluded from its own predecessor pool`() {
        // The candidate has the latest timestamp; without the self-exclusion it would be
        // its own predecessor (and, having no attachment, resolve to null spuriously).
        val photo = mms(10, "", ts = 1_000, withAttachment = true)
        val heart = mms(11, "❤️", ts = 2_000)
        assertEquals(10L, findBareEmojiReactionTarget(heart, listOf(photo, heart), 0, notFallback)?.id)
    }

    @Test fun `two photos then a heart attaches to the later photo`() {
        val photo1 = mms(10, "", ts = 1_000, withAttachment = true)
        val photo2 = mms(11, "", ts = 2_000, withAttachment = true)
        val heart = mms(12, "❤️", ts = 3_000)
        assertEquals(11L, findBareEmojiReactionTarget(heart, listOf(photo1, photo2, heart), 0, notFallback)?.id)
    }

    @Test fun `a second heart skips the first heart and attaches to the photo`() {
        // Other bare-emoji candidates are excluded from the pool, so the second heart's
        // predecessor is the photo, not the first heart.
        val photo = mms(10, "", ts = 1_000, withAttachment = true)
        val heart1 = mms(11, "❤️", ts = 2_000)
        val heart2 = mms(12, "❤️", ts = 3_000)
        assertEquals(10L, findBareEmojiReactionTarget(heart2, listOf(photo, heart1, heart2), 0, notFallback)?.id)
    }

    @Test fun `interleaved quoted-fallback rows are excluded from the preceding computation`() {
        val photo = mms(10, "", ts = 1_000, withAttachment = true)
        val quoted = mms(11, "👍 to \"nice pic\"", ts = 2_000)
        val heart = mms(12, "❤️", ts = 3_000)
        val isQuoted: (Message) -> Boolean = { it.id == 11L }
        assertEquals(10L, findBareEmojiReactionTarget(heart, listOf(photo, quoted, heart), 0, isQuoted)?.id)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun mms(
        id: Long,
        body: String,
        ts: Long = 0L,
        isSent: Boolean = false,
        withAttachment: Boolean = false
    ) = Message(
        id = id, threadId = 1L, address = "+15551234567", body = body, timestamp = ts,
        isSent = isSent, type = 1, isMms = true,
        attachments = if (withAttachment) listOf(MessageAttachment("content://mms/part/$id", "image/jpeg")) else emptyList()
    )

    private fun sms(id: Long, body: String, ts: Long = 0L) =
        Message(id = id, threadId = 1L, address = "+15551234567", body = body, timestamp = ts, isSent = false, type = 1)
}
