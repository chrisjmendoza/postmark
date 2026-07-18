package com.plusorminustwo.postmark.ui.conversations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [deleteResultMessage] — the Snackbar text shown after a bulk
 * conversation delete. Pins the singular/plural and all-failed vs partial-failure
 * branch selection (not the exact wording beyond what those branches assert).
 */
class DeleteResultMessageTest {

    @Test
    fun `single success is singular`() {
        assertEquals("1 conversation deleted", deleteResultMessage(deleted = 1, failed = 0))
    }

    @Test
    fun `multiple success is plural`() {
        assertEquals("3 conversations deleted", deleteResultMessage(deleted = 3, failed = 0))
    }

    @Test
    fun `all failed reports failure without a success count`() {
        val msg = deleteResultMessage(deleted = 0, failed = 2)
        assertTrue(msg, msg.contains("Couldn't delete"))
        assertTrue(msg, msg.contains("2"))
    }

    @Test
    fun `partial failure reports both counts`() {
        val msg = deleteResultMessage(deleted = 2, failed = 1)
        assertTrue(msg, msg.contains("2"))
        assertTrue(msg, msg.contains("1"))
    }
}
