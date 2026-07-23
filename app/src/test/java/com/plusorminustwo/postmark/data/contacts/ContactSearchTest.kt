package com.plusorminustwo.postmark.data.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for [formatContactSupportingText] (the contact-picker row label combiner —
 * see [Context.searchContacts] for the ContentResolver half, which needs an Android runtime).
 */
class ContactSearchTest {

    @Test fun `combines a type label and formatted number`() {
        assertEquals("Mobile · (206) 555-1234", formatContactSupportingText("Mobile", "(206) 555-1234"))
    }

    @Test fun `falls back to the number alone when the label is null`() {
        assertEquals("(206) 555-1234", formatContactSupportingText(null, "(206) 555-1234"))
    }

    @Test fun `falls back to the number alone when the label is blank`() {
        assertEquals("(206) 555-1234", formatContactSupportingText("", "(206) 555-1234"))
    }
}
