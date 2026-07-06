package com.plusorminustwo.postmark.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the JSON codec that persists a group MMS's List<String> of participant
 * addresses in the threads.participantsJson column. Mirrors MessageAttachmentCodecTest —
 * the decoder only has to understand what the encoder emits.
 */
class ThreadParticipantsCodecTest {

    @Test fun `empty list encodes to null`() {
        assertNull(encodeParticipantsJson(emptyList()))
    }

    @Test fun `single address encodes to null`() {
        // A 1:1 thread has exactly one participant — not worth storing as a group roster.
        assertNull(encodeParticipantsJson(listOf("+15551234567")))
    }

    @Test fun `null and blank decode to empty list`() {
        assertTrue(decodeParticipantsJson(null).isEmpty())
        assertTrue(decodeParticipantsJson("").isEmpty())
        assertTrue(decodeParticipantsJson("   ").isEmpty())
    }

    @Test fun `multiple addresses round-trip preserving order`() {
        val list = listOf("+15550000001", "+15550000002", "+15550000003")
        assertEquals(list, decodeParticipantsJson(encodeParticipantsJson(list)))
    }

    @Test fun `encoded form is a json array of strings`() {
        val json = encodeParticipantsJson(listOf("+1555", "+1666"))
        assertEquals("""["+1555","+1666"]""", json)
    }

    @Test fun `quotes and backslashes in an address survive the round-trip`() {
        val list = listOf("""weird"quote\address""", "+15559999999")
        assertEquals(list, decodeParticipantsJson(encodeParticipantsJson(list)))
    }

    @Test fun `garbage input without quotes decodes to empty list instead of throwing`() {
        assertTrue(decodeParticipantsJson("not json at all").isEmpty())
        assertTrue(decodeParticipantsJson("[]").isEmpty())
    }

    @Test fun `truncated JSON yields only the fully-quoted addresses found so far`() {
        // The decoder greedily reads to the next quote or end-of-string, so a value cut
        // off mid-string still gets included — unlike the key/value attachments codec,
        // there's no uri/mimeType pairing here to guard against a partial trailing entry.
        // Acceptable because decode only ever runs against encode's own output.
        assertEquals(listOf("only-half"), decodeParticipantsJson("[\"only-half"))
    }
}
