package com.plusorminustwo.postmark.service.sms

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [SmsManager] to send SMS messages and write the resulting sent row to the
 * system telephony content provider.
 *
 * As the default SMS app, Postmark is responsible for persisting sent messages so
 * other apps and the OS can see them. After writing the row [SmsContentObserver]
 * picks it up, syncs it to Room, and removes the optimistic entry created by
 * [ThreadViewModel].
 */
@Singleton
class SmsManagerWrapper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val smsManager: SmsManager
        get() = context.getSystemService(SmsManager::class.java)

    /**
     * Sends [text] to [destinationAddress] over the radio, writes the sent row to
     * `content://sms/sent`, and registers PendingIntent callbacks so that
     * [SmsSentDeliveryReceiver] can update the Room delivery status.
     *
     * @param messageId The optimistic Room row ID; used as the request code for
     *   PendingIntents so the receiver can identify which message to update.
     */
    fun sendTextMessage(destinationAddress: String, text: String, messageId: Long) {
        // ── Write to content://sms/sent ───────────────────────────────────────────
        // SmsManager only transmits over the radio; the default SMS app is
        // responsible for writing sent messages to the telephony content provider
        // so other apps (e.g. Google Messages) can see them. The SmsContentObserver
        // will pick up the new row, sync it into Room, and clean up the optimistic
        // entry created by ThreadViewModel.
        // ── Write to content://sms/sent and capture the assigned row ID ────────────────
        // We need the real positive row ID from the content provider so the delivery
        // callbacks in SmsSentDeliveryReceiver can update the correct Room row.  By the
        // time the sentIntent fires, SmsSyncHandler will have already replaced the
        // optimistic negative-ID entry with this positive-ID row.
        var smsRowId = -1L
        try {
            val now = System.currentTimeMillis()
            // getOrCreateThreadId ensures Samsung/MIUI devices assign the message to the
            // correct thread; omitting THREAD_ID can cause mis-grouping on those ROMs.
            val threadId = Telephony.Threads.getOrCreateThreadId(context, destinationAddress)
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, destinationAddress)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, now)
                // DATE_SENT = when the PDU left the device (best set to same value).
                put(Telephony.Sms.DATE_SENT, now)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.READ, 1)
                // SEEN = notification has been acknowledged; always 1 for our own sends.
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
                put(Telephony.Sms.THREAD_ID, threadId)
            }
            val insertedUri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            if (insertedUri != null) smsRowId = ContentUris.parseId(insertedUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sent message to content provider", e)
        }

        val reqBase = (messageId and 0x3FFF_FFFFL).toInt()
        // Capture in a local val so the lambdas below can close over it.
        val capturedSmsRowId = smsRowId

        fun sentIntent(offset: Int = 0) = PendingIntent.getBroadcast(
            context,
            reqBase + offset,
            Intent(context, SmsSentDeliveryReceiver::class.java).apply {
                action = SmsSentDeliveryReceiver.ACTION_SMS_SENT
                putExtra(SmsSentDeliveryReceiver.EXTRA_MESSAGE_ID, messageId)
                // Pass the real content-provider row ID so the receiver can update
                // the correct Room row (the optimistic row is deleted before it fires).
                putExtra(SmsSentDeliveryReceiver.EXTRA_SMS_ROW_ID, capturedSmsRowId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )

        fun deliveredIntent() = PendingIntent.getBroadcast(
            context,
            reqBase + 0x4000,
            Intent(context, SmsSentDeliveryReceiver::class.java).apply {
                action = SmsSentDeliveryReceiver.ACTION_SMS_DELIVERED
                putExtra(SmsSentDeliveryReceiver.EXTRA_MESSAGE_ID, messageId)
                putExtra(SmsSentDeliveryReceiver.EXTRA_SMS_ROW_ID, capturedSmsRowId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )

        val parts = smsManager.divideMessage(text)
        if (parts.size == 1) {
            smsManager.sendTextMessage(
                destinationAddress, null, text,
                sentIntent(), deliveredIntent()
            )
        } else {
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val deliveredIntents = ArrayList<PendingIntent?>(parts.size)
            parts.forEachIndexed { i, _ ->
                sentIntents.add(sentIntent(i))
                deliveredIntents.add(if (i == parts.lastIndex) deliveredIntent() else null)
            }
            smsManager.sendMultipartTextMessage(
                destinationAddress, null, parts, sentIntents, deliveredIntents
            )
        }
    }

    companion object {
        private const val TAG = "SmsManagerWrapper"
    }
}
