package com.plusorminustwo.postmark.service.sms

import com.plusorminustwo.postmark.service.sms.ConversationReadMarker.Table
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ConversationReadMarker.buildUpdates] — the decision of which telephony
 * table(s) to mark read, and how to scope the `WHERE`, when "Mark as read" is tapped on a
 * notification.
 *
 * The behavior under test is the MMS mark-read bug fix: a positive thread id must produce
 * a thread-scoped update against BOTH Sms and Mms, since MMS has no single stable sender
 * address to filter on. A missing thread id falls back to the historical address-scoped
 * Sms-only update.
 */
class ConversationReadMarkerTest {

    @Test
    fun `positive threadId marks both Sms and Mms scoped to the thread`() {
        val updates = ConversationReadMarker.buildUpdates(threadId = 42L, address = "555-0100")

        assertEquals(2, updates.size)

        val sms = updates.single { it.table == Table.SMS }
        assertEquals("thread_id = ? AND read = 0", sms.selection)
        assertEquals(listOf("42"), sms.selectionArgs)

        val mms = updates.single { it.table == Table.MMS }
        assertEquals("thread_id = ? AND read = 0", mms.selection)
        assertEquals(listOf("42"), mms.selectionArgs)
    }

    @Test
    fun `missing threadId falls back to address-scoped Sms-only update`() {
        val updates = ConversationReadMarker.buildUpdates(threadId = -1L, address = "555-0100")

        assertEquals(1, updates.size)
        val sms = updates.single()
        assertEquals(Table.SMS, sms.table)
        assertEquals("address = ? AND read = 0", sms.selection)
        assertEquals(listOf("555-0100"), sms.selectionArgs)
    }

    @Test
    fun `zero threadId is treated as missing`() {
        val updates = ConversationReadMarker.buildUpdates(threadId = 0L, address = "555-0100")

        assertEquals(1, updates.size)
        assertEquals(Table.SMS, updates.single().table)
    }
}
