package com.plusorminustwo.postmark.domain.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRedactionTest {

    @Test
    fun `E164 number keeps only last four digits`() {
        assertEquals("…1234", "+12065551234".redactPhone())
    }

    @Test
    fun `formatted NANP number keeps only last four digits`() {
        assertEquals("…1234", "(206) 555-1234".redactPhone())
    }

    @Test
    fun `bare ten digit number keeps only last four digits`() {
        assertEquals("…1234", "2065551234".redactPhone())
    }

    @Test
    fun `email address keeps only the domain`() {
        assertEquals("…@example.com", "someone@example.com".redactPhone())
    }

    @Test
    fun `email with digits in local part is still treated as email`() {
        assertEquals("…@carrier.net", "12065551234@carrier.net".redactPhone())
    }

    @Test
    fun `short code passes through unchanged`() {
        assertEquals("88202", "88202".redactPhone())
        assertEquals("8886", "8886".redactPhone())
    }

    @Test
    fun `seven digits is enough to be masked`() {
        assertEquals("…4567", "1234567".redactPhone())
    }

    @Test
    fun `empty string passes through`() {
        assertEquals("", "".redactPhone())
    }

    @Test
    fun `redaction is idempotent`() {
        val once = "+12065551234".redactPhone()
        assertEquals(once, once.redactPhone())
    }
}
