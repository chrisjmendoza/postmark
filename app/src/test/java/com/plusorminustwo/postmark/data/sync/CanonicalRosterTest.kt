package com.plusorminustwo.postmark.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure canonical-roster helpers (GROUP_MESSAGING_SPEC §1.5):
 * [parseRecipientIdList] (the space-separated `recipient_ids` parser) and
 * [repairAction] (the one-shot repair decision). No Android / content-resolver
 * involvement — [queryCanonicalParticipants] is the thin I/O wrapper around these.
 */
class CanonicalRosterTest {

    // ── parseRecipientIdList ────────────────────────────────────────────────

    @Test fun `normal space-separated ids parse in order`() {
        assertEquals(listOf(5L, 12L, 33L), parseRecipientIdList("5 12 33"))
    }

    @Test fun `single id parses to a one-element list`() {
        assertEquals(listOf(42L), parseRecipientIdList("42"))
    }

    @Test fun `empty and blank input parse to empty list`() {
        assertTrue(parseRecipientIdList("").isEmpty())
        assertTrue(parseRecipientIdList("   ").isEmpty())
    }

    @Test fun `doubled spaces and leading-trailing whitespace are tolerated`() {
        assertEquals(listOf(5L, 12L), parseRecipientIdList("  5   12  "))
    }

    @Test fun `tabs and newlines are treated as separators`() {
        assertEquals(listOf(1L, 2L, 3L), parseRecipientIdList("1\t2\n3"))
    }

    @Test fun `non-numeric tokens are dropped, numeric ones preserved`() {
        assertEquals(listOf(5L, 12L), parseRecipientIdList("5 x 12"))
        assertTrue(parseRecipientIdList("abc").isEmpty())
    }

    // ── repairAction ────────────────────────────────────────────────────────

    @Test fun `empty canonical demotes to 1-1`() {
        assertEquals(RepairAction.DEMOTE, repairAction(emptyList(), listOf("+1555", "+1666")))
    }

    @Test fun `single-recipient canonical demotes to 1-1`() {
        // The classic bug: stored 2-person roster (self included) but the OS says 1:1.
        assertEquals(RepairAction.DEMOTE, repairAction(listOf("+1555"), listOf("+1555", "+1666")))
    }

    @Test fun `canonical equal to current is kept`() {
        val roster = listOf("+1555", "+1666", "+1777")
        assertEquals(RepairAction.KEEP, repairAction(roster, roster))
    }

    @Test fun `different multi-recipient canonical replaces the roster`() {
        // e.g. old roster carried the user's own number alongside the real members.
        assertEquals(
            RepairAction.REPLACE,
            repairAction(listOf("+1555", "+1666"), listOf("+1555", "+1666", "+1self"))
        )
    }

    @Test fun `reordered roster of the same size replaces`() {
        assertEquals(
            RepairAction.REPLACE,
            repairAction(listOf("+1666", "+1555"), listOf("+1555", "+1666"))
        )
    }
}
