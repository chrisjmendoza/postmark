package com.plusorminustwo.postmark.service.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsMessage
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Required by Android for default-SMS-app eligibility.
 * Handles SMS send requests that arrive without a UI (e.g. from lock-screen quick-reply
 * or accessibility services). Routes through the same SmsManagerWrapper used everywhere
 * else, so delivery tracking works automatically.
 */
@AndroidEntryPoint
class HeadlessSmsSendService : Service() {

    @Inject lateinit var smsManager: SmsManagerWrapper

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // ── Extract destination address ──────────────────────────────────────────
        val uri  = intent.data
        val dest = uri?.schemeSpecificPart?.trimStart('/')?.trimStart('/') ?: run {
            Log.w(TAG, "HeadlessSmsSendService: no destination URI, ignoring")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // ── Extract message body from the SMS PDU bundle ──────────────────────────
        val messages = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringArrayExtra("pdus")
                ?.mapNotNull { pdu ->
                    SmsMessage.createFromPdu(
                        (pdu as? ByteArray) ?: return@mapNotNull null,
                        intent.getStringExtra("format")
                    )?.messageBody
                }
                ?.joinToString("")
            ?: run {
                Log.w(TAG, "HeadlessSmsSendService: no message body, ignoring")
                stopSelf(startId)
                return START_NOT_STICKY
            }

        // ── Send — use 0 as a placeholder message ID (no optimistic DB insert) ───
        Log.d(TAG, "Sending headless SMS to $dest (${messages.length} chars)")
        try {
            smsManager.sendTextMessage(dest, messages, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "Headless send failed: ${e.message}", e)
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "HeadlessSmsSend"
    }
}
