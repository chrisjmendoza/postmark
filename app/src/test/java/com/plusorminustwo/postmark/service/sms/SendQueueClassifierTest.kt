package com.plusorminustwo.postmark.service.sms

import android.telephony.SmsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isQueueWorthyFailure] — the pure classifier deciding whether a failed
 * SMS send should be parked in the offline send queue (transient no-service / radio-off)
 * or surfaced as a genuine FAILED. The [SmsManager] result codes are compile-time
 * constants, so this runs on the JVM without touching any Android runtime.
 */
class SendQueueClassifierTest {

    @Test
    fun `no service is queue-worthy`() {
        assertTrue(isQueueWorthyFailure(SmsManager.RESULT_ERROR_NO_SERVICE))
    }

    @Test
    fun `radio off is queue-worthy`() {
        assertTrue(isQueueWorthyFailure(SmsManager.RESULT_ERROR_RADIO_OFF))
    }

    @Test
    fun `generic failure is not queue-worthy`() {
        assertFalse(isQueueWorthyFailure(SmsManager.RESULT_ERROR_GENERIC_FAILURE))
    }

    @Test
    fun `null pdu is not queue-worthy`() {
        assertFalse(isQueueWorthyFailure(SmsManager.RESULT_ERROR_NULL_PDU))
    }

    @Test
    fun `limit exceeded is not queue-worthy`() {
        assertFalse(isQueueWorthyFailure(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED))
    }

    @Test
    fun `RESULT_OK is not queue-worthy`() {
        // -1; a success never reaches the failure classifier, but it must not be queue-worthy.
        assertFalse(isQueueWorthyFailure(android.app.Activity.RESULT_OK))
    }
}
