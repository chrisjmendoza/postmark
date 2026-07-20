package com.plusorminustwo.postmark.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table tests for the New Conversation multi-recipient chip logic (GROUP_MESSAGING_SPEC
 * §3): normalizing addresses for dedupe comparison, and the pure add/remove decisions
 * the chip strip is built from.
 */
class RecipientSelectionTest {

    // ── normalizeAddressForDedupe ────────────────────────────────────────────────

    @Test fun `E164 NANP number normalizes to bare 10 digits`() {
        assertEquals("2065551234", normalizeAddressForDedupe("+12065551234"))
    }

    @Test fun `11-digit number with leading 1 and no plus normalizes the same`() {
        assertEquals("2065551234", normalizeAddressForDedupe("12065551234"))
    }

    @Test fun `formatted 10-digit number normalizes to the same key`() {
        assertEquals("2065551234", normalizeAddressForDedupe("(206) 555-1234"))
    }

    @Test fun `plain 10-digit number normalizes unchanged`() {
        assertEquals("2065551234", normalizeAddressForDedupe("2065551234"))
    }

    @Test fun `short code passes through as digits only`() {
        assertEquals("12345", normalizeAddressForDedupe("12345"))
    }

    @Test fun `non-NANP international number is not truncated`() {
        // 11 digits but does not start with the NANP "1" prefix — left as-is.
        assertEquals("44207946123", normalizeAddressForDedupe("+44207946123"))
    }

    // ── addRecipient ──────────────────────────────────────────────────────────────

    @Test fun `addRecipient appends a new chip`() {
        val current = listOf(RecipientChip("+12065551234", "Alice"))
        val result = addRecipient(current, RecipientChip("+12065559999", "Bob"))
        assertEquals(listOf("Alice", "Bob"), result.map { it.displayName })
    }

    @Test fun `addRecipient dedupes an exact-match address`() {
        val current = listOf(RecipientChip("+12065551234", "Alice"))
        val result = addRecipient(current, RecipientChip("+12065551234", "Alice (again)"))
        assertEquals(current, result)
    }

    @Test fun `addRecipient dedupes the same person with different formatting`() {
        val current = listOf(RecipientChip("(206) 555-1234", "Alice"))
        val result = addRecipient(current, RecipientChip("+12065551234", "Alice via search"))
        assertEquals(1, result.size)
        assertEquals("Alice", result.first().displayName)
    }

    @Test fun `addRecipient ignores a blank address`() {
        val current = listOf(RecipientChip("+12065551234", "Alice"))
        assertEquals(current, addRecipient(current, RecipientChip("", "")))
    }

    @Test fun `addRecipient on an empty list starts the strip`() {
        val result = addRecipient(emptyList(), RecipientChip("+12065551234", "Alice"))
        assertEquals(listOf(RecipientChip("+12065551234", "Alice")), result)
    }

    // ── removeRecipient ───────────────────────────────────────────────────────────

    @Test fun `removeRecipient drops the matching chip`() {
        val current = listOf(RecipientChip("+12065551234", "Alice"), RecipientChip("+12065559999", "Bob"))
        val result = removeRecipient(current, "+12065551234")
        assertEquals(listOf(RecipientChip("+12065559999", "Bob")), result)
    }

    @Test fun `removeRecipient matches across formatting differences`() {
        val current = listOf(RecipientChip("+12065551234", "Alice"))
        assertEquals(emptyList<RecipientChip>(), removeRecipient(current, "(206) 555-1234"))
    }

    @Test fun `removeRecipient is a no-op when the address isn't present`() {
        val current = listOf(RecipientChip("+12065551234", "Alice"))
        assertEquals(current, removeRecipient(current, "+19995550000"))
    }
}
