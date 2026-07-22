package com.plusorminustwo.postmark.service.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [shouldRecoverSentRow] — SmsSentDeliveryReceiver re-creates the
 * content://sms/sent row when the radio confirmed the send but the pre-send
 * insert produced no row. (Formerly half of SentRowRepairTest; the MMS half
 * died with mmsThreadIdNeedsRepair when persistSentMms made repair obsolete.)
 */
class SmsSentRowRecoveryTest {

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
}
