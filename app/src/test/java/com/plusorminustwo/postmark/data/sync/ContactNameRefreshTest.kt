package com.plusorminustwo.postmark.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [resolveDisplayNameRefresh], the pure decision behind the contact-name refresh
 * pass (docs/TODO.md Tier 2 "Contact integration"). No Android / content-resolver
 * involvement — [SmsSyncHandler.refreshOneToOneDisplayNames] is the thin I/O wrapper that
 * feeds this the stored name, the live-resolved name, and the group flag.
 */
class ContactNameRefreshTest {

    @Test fun `renamed contact returns the new name`() {
        // Stored name was resolved when the contact was "Alice"; contact renamed to "Alice Smith".
        assertEquals("Alice Smith", resolveDisplayNameRefresh("Alice", "Alice Smith", isGroup = false))
    }

    @Test fun `bare number that just gained a contact updates to the contact name`() {
        assertEquals("Alice", resolveDisplayNameRefresh("+12065550100", "Alice", isGroup = false))
    }

    @Test fun `unchanged contact name is a no-op`() {
        assertNull(resolveDisplayNameRefresh("Alice", "Alice", isGroup = false))
    }

    @Test fun `deleted contact (null resolved) keeps the stored name`() {
        // Chosen behavior: KEEP. lookupContactName returns null for a deleted contact AND for a
        // transient permission/provider failure, so we never revert a good name to a number.
        assertNull(resolveDisplayNameRefresh("Alice", null, isGroup = false))
    }

    @Test fun `blank resolved name keeps the stored name`() {
        assertNull(resolveDisplayNameRefresh("Alice", "", isGroup = false))
    }

    @Test fun `group thread is never touched even when the resolved name differs`() {
        // Group display names are comma-joined rosters owned by the roster-staleness mechanism.
        assertNull(resolveDisplayNameRefresh("Alice, Bob", "Alice Smith", isGroup = true))
    }
}
