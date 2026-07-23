package com.plusorminustwo.postmark.service.sms

import android.telephony.SmsManager

/**
 * Pure classifier for the offline send queue.
 *
 * A send that fails purely because the phone had no cell service is not a real failure —
 * the message can go out unchanged as soon as service returns. Only those transient,
 * connectivity-driven [SmsManager] result codes are "queue-worthy": everything else
 * (generic failure, null PDU, limit exceeded, FDN check, short-code refusal, …) is a
 * genuine failure that must still surface as FAILED with the red retry indicator.
 *
 * No Android runtime is touched — the [SmsManager] result codes are compile-time
 * constants inlined at the call site — so this is exercised directly in
 * [com.plusorminustwo.postmark.service.sms.SendQueueClassifierTest].
 */
fun isQueueWorthyFailure(resultCode: Int): Boolean =
    resultCode == SmsManager.RESULT_ERROR_NO_SERVICE ||
        resultCode == SmsManager.RESULT_ERROR_RADIO_OFF
