package com.plusorminustwo.postmark.domain.selection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [bulkToggleTarget] — the apply-to-all decision behind the
 * conversation-list Pin/Unpin and Mute/Unmute bulk actions.
 *
 * Rule: if ANY selected thread is currently off, turn them all on; only when every
 * one is already on does the action turn them all off.
 */
class BulkToggleTest {

    @Test
    fun `all off turns all on`() {
        assertTrue(bulkToggleTarget(listOf(false, false, false)))
    }

    @Test
    fun `all on turns all off`() {
        assertFalse(bulkToggleTarget(listOf(true, true, true)))
    }

    @Test
    fun `mixed selection turns all on`() {
        // A single off thread is enough to make the action "turn on all".
        assertTrue(bulkToggleTarget(listOf(true, false, true)))
    }

    @Test
    fun `single off thread turns on`() {
        assertTrue(bulkToggleTarget(listOf(false)))
    }

    @Test
    fun `single on thread turns off`() {
        assertFalse(bulkToggleTarget(listOf(true)))
    }

    @Test
    fun `empty selection returns off`() {
        // Empty is a no-op in practice (nothing selected), but must not throw.
        assertFalse(bulkToggleTarget(emptyList()))
    }
}
