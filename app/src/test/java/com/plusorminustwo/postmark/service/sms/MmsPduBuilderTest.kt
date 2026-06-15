package com.plusorminustwo.postmark.service.sms

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Tests for the WAP Binary UintVar encoder used throughout MmsPduBuilder.
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
}
