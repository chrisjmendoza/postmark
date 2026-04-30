package com.plusorminustwo.postmark.domain.formatter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [formatPhoneNumber].
 *
 * Covers:
 *  - US E.164 numbers formatted as NANP (206) 555-1234
 *  - Plain 10-digit NANP numbers
 *  - International non-NANP numbers — pass-through
 *  - Short codes (≤6 digits) — pass-through
 *  - Empty / blank strings — pass-through
 */
class PhoneNumberFormatterTest {

    // ── US E.164 numbers ─────────────────────────────────────────────────────

    @Test
    fun `E164 US number is formatted as NANP`() {
        assertEquals("(206) 555-1234", formatPhoneNumber("+12065551234"))
    }

    @Test
    fun `E164 US number with different area code`() {
        assertEquals("(415) 555-9876", formatPhoneNumber("+14155559876"))
    }

    @Test
    fun `E164 Canadian number is formatted as NANP`() {
        assertEquals("(604) 555-4321", formatPhoneNumber("+16045554321"))
    }

    // ── Plain 10-digit NANP ───────────────────────────────────────────────────

    @Test
    fun `plain 10 digit number is formatted`() {
        assertEquals("(206) 555-1234", formatPhoneNumber("2065551234"))
    }

    // ── International (non-NANP) — pass-through ──────────────────────────────

    @Test
    fun `UK number passes through unchanged`() {
        assertEquals("+447911123456", formatPhoneNumber("+447911123456"))
    }

    @Test
    fun `German number passes through unchanged`() {
        assertEquals("+4915123456789", formatPhoneNumber("+4915123456789"))
    }

    @Test
    fun `international number with no country prefix passes through when not 10 digits`() {
        assertEquals("+3312345678", formatPhoneNumber("+3312345678"))
    }

    // ── Short codes — pass-through ────────────────────────────────────────────

    @Test
    fun `5 digit short code passes through`() {
        assertEquals("12345", formatPhoneNumber("12345"))
    }

    @Test
    fun `6 digit short code passes through`() {
        assertEquals("123456", formatPhoneNumber("123456"))
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `empty string passes through`() {
        assertEquals("", formatPhoneNumber(""))
    }

    @Test
    fun `blank string passes through`() {
        assertEquals("  ", formatPhoneNumber("  "))
    }

    @Test
    fun `non-numeric string passes through`() {
        assertEquals("Alice", formatPhoneNumber("Alice"))
    }

    @Test
    fun `locale parameter does not affect NANP output`() {
        val result = formatPhoneNumber("+12065551234", Locale.UK)
        assertEquals("(206) 555-1234", result)
    }
}
