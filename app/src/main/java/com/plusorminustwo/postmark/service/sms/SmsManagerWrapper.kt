package com.plusorminustwo.postmark.service.sms

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [SmsManager] to send SMS messages and write the resulting sent row to the
 * system telephony content provider.
 *
 * As the default SMS app, Postmark is responsible for persisting sent messages so
 * other apps and the OS can see them. After writing the row, [SmsSyncHandler] is
 * triggered explicitly (and [SmsContentObserver] may also fire) to sync the real
 * row into Room and remove the optimistic entry created by [ThreadViewModel].
 */
@Singleton
class SmsManagerWrapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsSyncHandler: SmsSyncHandler
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
        // SmsManager transmits over the radio; as default SMS app we must write the row
        // to content://sms/sent so other apps can see it. We also capture the assigned
        // row ID so SmsSentDeliveryReceiver can update the correct Room row (the optimistic
        // negative-ID entry will have been replaced by SmsSyncHandler before that fires).
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

        /* Explicitly trigger incremental SMS sync — mirrors what SmsReceiver does for
         * incoming messages. After an Android/Samsung system update the content-observer
         * notification chain from content://sms/sent → content://sms became unreliable,
         * so we can no longer rely solely on SmsContentObserver to kick off the sync.
         * This call is idempotent: if the observer already fired the sync, SmsSyncHandler's
         * CONFLATED channel simply drops the duplicate signal. */
        smsSyncHandler.onSmsContentChanged(Telephony.Sms.CONTENT_URI)

        val reqBase = (messageId and 0x3FFF_FFFFL).toInt()
        // Capture in a local val so the lambdas below can close over it.
        val capturedSmsRowId = smsRowId

        fun sentIntent(offset: Int = 0, isLastPart: Boolean = true) = PendingIntent.getBroadcast(
            context,
            reqBase + offset,
            Intent(context, SmsSentDeliveryReceiver::class.java).apply {
                action = SmsSentDeliveryReceiver.ACTION_SMS_SENT
                putExtra(SmsSentDeliveryReceiver.EXTRA_MESSAGE_ID, messageId)
                // Pass the real content-provider row ID so the receiver can update
                // the correct Room row (the optimistic row is deleted before it fires).
                putExtra(SmsSentDeliveryReceiver.EXTRA_SMS_ROW_ID, capturedSmsRowId)
                /* Recovery payload: if the provider insert above threw (smsRowId == -1),
                 * the receiver re-creates the missing content://sms/sent row once the
                 * radio confirms the send — otherwise the message is delivered but
                 * invisible to every reader of the system provider. Only the final
                 * part carries it so multipart messages recover exactly one row. */
                if (isLastPart) {
                    putExtra(SmsSentDeliveryReceiver.EXTRA_ADDRESS, destinationAddress)
                    putExtra(SmsSentDeliveryReceiver.EXTRA_BODY, text)
                }
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
                sentIntents.add(sentIntent(i, isLastPart = i == parts.lastIndex))
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
