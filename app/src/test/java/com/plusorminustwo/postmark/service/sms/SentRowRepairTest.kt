package com.plusorminustwo.postmark.service.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure repair-decision logic behind the June 2026 "sent messages
 * missing from the system provider" fixes:
 *
 *  - [shouldRecoverSentRow] — SmsSentDeliveryReceiver re-creates the content://sms/sent
 *    row when the radio confirmed the send but the pre-send insert produced no row.
 *  - [mmsThreadIdNeedsRepair] — MmsSentReceiver corrects the platform-assigned
 *    thread_id on the sent content://mms row when it doesn't match the canonical
 *    thread id for the recipient.
 */
class SentRowRepairTest {

    // ── shouldRecoverSentRow ──────────────────────────────────────────────────

    @Test
    fun `recover when send succeeded, row missing, address present`() {
        assertTrue(shouldRecoverSentRow(sendSucceeded = true, smsRowId = -1L, address = "+12065550100"))
    }

    @Test
    fun `recover when insert returned zero row id`() {
        assertTrue(shouldRecoverSentRow(sendSucceeded = true, smsRowId = 0L, address = "+12065550100"))
    }

    @Test
    fun `no recovery when send failed`() {
        assertFalse(shouldRecoverSentRow(sendSucceeded = false, smsRowId = -1L, address = "+12065550100"))
    }

    @Test
    fun `no recovery when the pre-send insert succeeded`() {
        assertFalse(shouldRecoverSentRow(sendSucceeded = true, smsRowId = 42L, address = "+12065550100"))
    }

    @Test
    fun `no recovery without an address extra (legacy PendingIntent)`() {
        assertFalse(shouldRecoverSentRow(sendSucceeded = true, smsRowId = -1L, address = null))
    }

    @Test
    fun `no recovery for empty address`() {
        assertFalse(shouldRecoverSentRow(sendSucceeded = true, smsRowId = -1L, address = ""))
    }

    // ── mmsThreadIdNeedsRepair ────────────────────────────────────────────────

    @Test
    fun `repair when platform assigned a different thread id`() {
        assertTrue(mmsThreadIdNeedsRepair(rowThreadId = 7L, expectedThreadId = 12L))
    }

    @Test
    fun `repair when platform left thread id unassigned (zero)`() {
        assertTrue(mmsThreadIdNeedsRepair(rowThreadId = 0L, expectedThreadId = 12L))
    }

    @Test
    fun `no repair when thread id already correct`() {
        assertFalse(mmsThreadIdNeedsRepair(rowThreadId = 12L, expectedThreadId = 12L))
    }

    @Test
    fun `never repair toward an invalid expected id (zero)`() {
        assertFalse(mmsThreadIdNeedsRepair(rowThreadId = 7L, expectedThreadId = 0L))
    }

    @Test
    fun `never repair toward an invalid expected id (negative)`() {
        assertFalse(mmsThreadIdNeedsRepair(rowThreadId = 7L, expectedThreadId = -1L))
    }
}
