package com.plusorminustwo.postmark.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table tests for [recipientsFor] — the pure send-routing decision used by both the
 * send and retry paths (GROUP_MESSAGING_SPEC §2.3). A group thread reaches its whole
 * roster; a 1:1 thread reaches its single address.
 */
class RecipientsForTest {

    private fun thread(address: String, participants: List<String>) = Thread(
        id = 1L,
        displayName = "test",
        address = address,
        lastMessageAt = 0L,
        participants = participants
    )

    @Test fun `group thread returns the full roster in order`() {
        val roster = listOf("+15551110001", "+15551110002", "+15551110003")
        assertEquals(roster, recipientsFor(thread("+15551110001", roster)))
    }

    @Test fun `two-participant thread is still a group`() {
        val roster = listOf("+15551110001", "+15551110002")
        assertEquals(roster, recipientsFor(thread("+15551110001", roster)))
    }

    @Test fun `1 to 1 thread with empty participants returns the single address`() {
        assertEquals(listOf("+15551234567"), recipientsFor(thread("+15551234567", emptyList())))
    }

    @Test fun `single-element participants list falls back to the address`() {
        // A 1:1 thread never stores a roster, but guard the size==1 boundary anyway:
        // one participant is not a group, so route to the thread address.
        assertEquals(listOf("+15559990000"), recipientsFor(thread("+15559990000", listOf("+15551110001"))))
    }
}
