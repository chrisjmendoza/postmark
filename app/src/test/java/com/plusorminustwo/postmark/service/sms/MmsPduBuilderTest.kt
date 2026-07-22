package com.plusorminustwo.postmark.service.sms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Tests for MmsPduBuilder: the WAP Binary UintVar encoder, the SMIL builder,
 * and multi-part PDU assembly.
 *
 * UintVar is the foundation of every field length in the PDU — an off-by-one
 * or wrong continuation-bit destroys all MMS sends silently.
 *
 * Encoding rules (WSP §8.1.2):
 *   - Values 0–127 fit in a single byte (MSB = 0).
 *   - Larger values use multiple bytes, MSB = 1 on all but the last byte.
 *   - 7 payload bits per byte, most-significant group first.
 */
class MmsPduBuilderTest {

    private fun encodeUintVar(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeUintVar(value)
        return out.toByteArray()
    }

    @Test fun `zero encodes to single zero byte`() {
        assertArrayEquals(byteArrayOf(0x00), encodeUintVar(0))
    }

    @Test fun `127 (0x7F) is max single-byte value`() {
        assertArrayEquals(byteArrayOf(0x7F), encodeUintVar(127))
    }

    @Test fun `128 (0x80) is min two-byte value`() {
        // 128 = 0b_0000001_0000000 → [0x81, 0x00]
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x00), encodeUintVar(128))
    }

    @Test fun `255 encodes to two bytes`() {
        // 255 = 0b_0000001_1111111 → [0x81, 0x7F]
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x7F), encodeUintVar(255))
    }

    @Test fun `256 encodes to two bytes`() {
        // 256 = 0b_0000010_0000000 → [0x82, 0x00]
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x00), encodeUintVar(256))
    }

    @Test fun `16383 (0x3FFF) is max two-byte value`() {
        // 16383 = 0b_1111111_1111111 → [0xFF, 0x7F]
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), encodeUintVar(16383))
    }

    @Test fun `16384 (0x4000) is min three-byte value`() {
        // 16384 = 0b_0000001_0000000_0000000 → [0x81, 0x80, 0x00]
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x00), encodeUintVar(16384))
    }

    @Test fun `2097151 (0x1FFFFF) is max three-byte value`() {
        // 2097151 = 0b_1111111_1111111_1111111 → [0xFF, 0xFF, 0x7F]
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F), encodeUintVar(2097151))
    }

    @Test fun `typical part header length (around 50 bytes) encodes correctly`() {
        // 50 fits in a single byte
        assertArrayEquals(byteArrayOf(50), encodeUintVar(50))
    }

    @Test fun `typical media payload size (around 200 KB) encodes correctly`() {
        // 200_000 % 128 = 64; 1562 % 128 = 26; 12 % 128 = 12
        // 7-bit groups LSB→MSB: [64, 26, 12] → reversed with continuation: [0x8C, 0x9A, 0x40]
        // Verify: 12 * 128^2 + 26 * 128 + 64 = 196608 + 3328 + 64 = 200000 ✓
        assertArrayEquals(byteArrayOf(0x8C.toByte(), 0x9A.toByte(), 0x40), encodeUintVar(200_000))
    }

    // ── mediaFilename — unique Content-Location per part ────────────────────

    @Test fun `mediaFilename embeds the part index for every mime family`() {
        assertEquals("image0.jpg", MmsPduBuilder.mediaFilename("image/jpeg", 0))
        assertEquals("image1.png", MmsPduBuilder.mediaFilename("image/png", 1))
        assertEquals("image2.gif", MmsPduBuilder.mediaFilename("image/gif", 2))
        assertEquals("video1.mp4", MmsPduBuilder.mediaFilename("video/mp4", 1))
        assertEquals("video0.3gp", MmsPduBuilder.mediaFilename("video/3gpp", 0))
        assertEquals("audio0.amr", MmsPduBuilder.mediaFilename("audio/amr", 0))
        assertEquals("attachment3.bin", MmsPduBuilder.mediaFilename("application/pdf", 3))
    }

    @Test fun `mediaFilename is unique across parts of the same mime type`() {
        val names = (0..4).map { MmsPduBuilder.mediaFilename("image/jpeg", it) }
        assertEquals(names.size, names.toSet().size)
    }

    // ── buildSmil — multi-part presentation ─────────────────────────────────

    @Test fun `smil references every media filename in order`() {
        val smil = MmsPduBuilder.buildSmil(
            listOf("image0.jpg" to "image/jpeg", "image1.png" to "image/png", "video2.mp4" to "video/mp4"),
            hasText = false
        )
        val i0 = smil.indexOf("""<img src="image0.jpg" region="Media"/>""")
        val i1 = smil.indexOf("""<img src="image1.png" region="Media"/>""")
        val i2 = smil.indexOf("""<video src="video2.mp4" region="Media"/>""")
        assertTrue(i0 >= 0); assertTrue(i1 > i0); assertTrue(i2 > i1)
    }

    @Test fun `smil emits one par slide per media part`() {
        val smil = MmsPduBuilder.buildSmil(
            listOf("image0.jpg" to "image/jpeg", "image1.jpg" to "image/jpeg"),
            hasText = false
        )
        assertEquals(2, Regex("<par ").findAll(smil).count())
    }

    @Test fun `smil places the text caption on the first slide only`() {
        val smil = MmsPduBuilder.buildSmil(
            listOf("image0.jpg" to "image/jpeg", "image1.jpg" to "image/jpeg"),
            hasText = true
        )
        assertEquals(1, Regex("<text ").findAll(smil).count())
        val firstParEnd = smil.indexOf("</par>")
        assertTrue(smil.indexOf("<text ") in 0 until firstParEnd)
        assertTrue(smil.contains("""<region id="Text""""))
    }

    @Test fun `smil for images uses shared Media region and shrinks it when text present`() {
        val noText = MmsPduBuilder.buildSmil(listOf("image0.jpg" to "image/jpeg"), hasText = false)
        assertTrue(noText.contains("""<region id="Media" width="100%" height="100%""""))
        val withText = MmsPduBuilder.buildSmil(listOf("image0.jpg" to "image/jpeg"), hasText = true)
        assertTrue(withText.contains("""<region id="Media" width="100%" height="80%""""))
    }

    @Test fun `smil for audio-only has no visible region and indefinite duration`() {
        val smil = MmsPduBuilder.buildSmil(listOf("audio0.amr" to "audio/amr"), hasText = false)
        assertFalse(smil.contains("""<region id="Media""""))
        assertTrue(smil.contains("""<par dur="indefinite"><audio src="audio0.amr"/></par>"""))
    }

    @Test fun `smil durations are 5s for images and indefinite for video`() {
        val smil = MmsPduBuilder.buildSmil(
            listOf("image0.jpg" to "image/jpeg", "video1.mp4" to "video/mp4"),
            hasText = false
        )
        assertTrue(smil.contains("""<par dur="5000ms"><img src="image0.jpg""""))
        assertTrue(smil.contains("""<par dur="indefinite"><video src="video1.mp4""""))
    }

    // ── buildPdu — N media parts with unique Content-Ids ────────────────────

    private fun mediaPart(mime: String, marker: Byte) =
        MmsPduBuilder.MediaPart(ByteArray(16) { marker }, mime)

    /** Counts non-overlapping occurrences of [needle] (ASCII) in this byte array. */
    private fun ByteArray.countOf(needle: String): Int {
        val n = needle.toByteArray(Charsets.US_ASCII)
        var count = 0
        var i = 0
        outer@ while (i <= size - n.size) {
            for (j in n.indices) {
                if (this[i + j] != n[j]) { i++; continue@outer }
            }
            count++; i += n.size
        }
        return count
    }

    @Test fun `pdu contains one uniquely numbered content-id per media part`() {
        val pdu = MmsPduBuilder.buildPdu(
            toAddresses = listOf("+15551234567"),
            mediaParts = listOf(
                mediaPart("image/jpeg", 0x11),
                mediaPart("image/png",  0x22),
                mediaPart("video/mp4",  0x33)
            ),
            textBody = ""
        )
        assertEquals(1, pdu.countOf("<media0>"))
        assertEquals(1, pdu.countOf("<media1>"))
        assertEquals(1, pdu.countOf("<media2>"))
        assertEquals(0, pdu.countOf("<media3>"))
    }

    @Test fun `pdu contains each media payload and its unique filename`() {
        val pdu = MmsPduBuilder.buildPdu(
            toAddresses = listOf("+15551234567"),
            mediaParts = listOf(mediaPart("image/jpeg", 0x5A), mediaPart("image/jpeg", 0x6B)),
            textBody   = ""
        )
        // Content-Location filenames: once in the part header, once in the SMIL <img src=…>.
        assertEquals(2, pdu.countOf("image0.jpg"))
        assertEquals(2, pdu.countOf("image1.jpg"))
        // Raw payloads present verbatim.
        assertEquals(1, pdu.countOf(String(ByteArray(16) { 0x5A }, Charsets.US_ASCII)))
        assertEquals(1, pdu.countOf(String(ByteArray(16) { 0x6B }, Charsets.US_ASCII)))
    }

    @Test fun `pdu includes text part only when caption present`() {
        val withText = MmsPduBuilder.buildPdu(
            listOf("+15551234567"), listOf(mediaPart("image/jpeg", 0x01)), "hello"
        )
        assertEquals(1, withText.countOf("<txt>"))
        assertEquals(1, withText.countOf("hello"))
        val noText = MmsPduBuilder.buildPdu(
            listOf("+15551234567"), listOf(mediaPart("image/jpeg", 0x01)), ""
        )
        assertEquals(0, noText.countOf("<txt>"))
    }

    @Test fun `pdu smil start part is present exactly once`() {
        val pdu = MmsPduBuilder.buildPdu(
            listOf("+15551234567"),
            listOf(mediaPart("image/jpeg", 0x01), mediaPart("video/mp4", 0x02)),
            "caption"
        )
        // "application/smil" appears exactly twice: the top-level Content-Type "type"
        // parameter and the SMIL part's own Content-Type (Extension-media string).
        assertEquals(2, pdu.countOf("application/smil"))
        assertEquals(1, pdu.countOf("smil.xml"))
    }

    // ── buildPdu — group (multiple To headers) ──────────────────────────────

    /** First index of [needle] (ASCII) in this byte array, or -1. */
    private fun ByteArray.indexOf(needle: String): Int {
        val n = needle.toByteArray(Charsets.US_ASCII)
        var i = 0
        outer@ while (i <= size - n.size) {
            for (j in n.indices) if (this[i + j] != n[j]) { i++; continue@outer }
            return i
        }
        return -1
    }

    @Test fun `pdu writes one To header per recipient in recipient order`() {
        val recipients = listOf("+15551110001", "+15551110002", "+15551110003")
        val pdu = MmsPduBuilder.buildPdu(recipients, listOf(mediaPart("image/jpeg", 0x01)), "")
        // One /TYPE=PLMN-suffixed To value per recipient.
        assertEquals(3, pdu.countOf("/TYPE=PLMN"))
        // Each address appears in order, immediately preceded by the FIELD_TO byte (0x97).
        var prev = -1
        for (r in recipients) {
            val idx = pdu.indexOf("$r/TYPE=PLMN")
            assertTrue("recipient $r missing from PDU", idx >= 0)
            assertTrue("recipients out of order at $r", idx > prev)
            assertEquals("To header byte (0x97) missing before $r", 0x97.toByte(), pdu[idx - 1])
            prev = idx
        }
    }

    @Test fun `single-recipient pdu writes exactly one To header`() {
        val pdu = MmsPduBuilder.buildPdu(listOf("+15551234567"), listOf(mediaPart("image/jpeg", 0x01)), "")
        assertEquals(1, pdu.countOf("/TYPE=PLMN"))
        val idx = pdu.indexOf("+15551234567/TYPE=PLMN")
        assertTrue(idx >= 0)
        assertEquals(0x97.toByte(), pdu[idx - 1])
    }

    @Test fun `buildPdu rejects an empty recipient list`() {
        try {
            MmsPduBuilder.buildPdu(emptyList(), listOf(mediaPart("image/jpeg", 0x01)), "")
            org.junit.Assert.fail("expected IllegalArgumentException for empty recipients")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    // ── buildPdu — text-only (no media parts) ───────────────────────────────

    @Test fun `text-only pdu has valid smil and text part but no media`() {
        val pdu = MmsPduBuilder.buildPdu(listOf("+15551234567"), emptyList(), "just text")
        // SMIL start part still present (top-level type param + the SMIL part's own CT).
        assertEquals(2, pdu.countOf("application/smil"))
        assertEquals(1, pdu.countOf("smil.xml"))
        // The text/plain part and its body are present; no media part/filename exists.
        assertEquals(1, pdu.countOf("<txt>"))
        assertEquals(1, pdu.countOf("just text"))
        assertEquals(0, pdu.countOf("<media0>"))
        assertEquals(0, pdu.countOf("image0"))
        // text/plain Content-General-Form with charset=UTF-8 (0x03,0x83,0x81,0xEA) — a
        // well-formed Content-Type so accented/emoji captions don't arrive as mojibake.
        assertTrue(
            "text/plain UTF-8 Content-Type header missing",
            pdu.indexOfBytes(byteArrayOf(0x03, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte())) >= 0
        )
    }

    /** First index of the exact byte sequence [needle] in this array, or -1. */
    private fun ByteArray.indexOfBytes(needle: ByteArray): Int {
        var i = 0
        outer@ while (i <= size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) { i++; continue@outer }
            return i
        }
        return -1
    }

    @Test fun `buildSmil for text-only has a Text region and text slide but no Media region`() {
        val smil = MmsPduBuilder.buildSmil(emptyList(), hasText = true)
        assertFalse(smil.contains("""<region id="Media""""))
        assertTrue(smil.contains("""<region id="Text""""))
        assertEquals(1, Regex("<par ").findAll(smil).count())
        assertTrue(smil.contains("""<text src="text.txt" region="Text"/>"""))
    }
}
