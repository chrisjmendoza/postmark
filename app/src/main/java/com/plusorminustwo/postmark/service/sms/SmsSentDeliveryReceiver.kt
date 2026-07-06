package com.plusorminustwo.postmark.service.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import com.plusorminustwo.postmark.data.sync.SyncLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [BroadcastReceiver] that handles SMS sent and delivery confirmations.
 *
 * Receives the [PendingIntent]s registered by [SmsManagerWrapper.sendTextMessage]:
 *  - **Sent intent** (`ACTION_SMS_SENT`): updates the Room delivery status to
 *    [DELIVERY_STATUS_SENT] or [DELIVERY_STATUS_FAILED] and mirrors the result to
 *    the system telephony content provider.
 *  - **Delivery intent** (`ACTION_SMS_DELIVERED`): updates Room to
 *    [DELIVERY_STATUS_DELIVERED] when the SMSC acknowledges the handset receipt.
 *
 * Uses [smsRowId] (the positive content-provider row ID captured at send time) as
 * the canonical Room key, falling back to the optimistic `messageId` only if the
 * extra is absent.
 */
@AndroidEntryPoint
class SmsSentDeliveryReceiver : BroadcastReceiver() {

    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var syncLogger: SyncLogger
    @Inject lateinit var smsSyncHandler: SmsSyncHandler

    override fun onReceive(context: Context, intent: Intent) {
        val messageId  = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        /* smsRowId is the positive content-provider _id captured at insert time in
         * SmsManagerWrapper. By the time this fires, SmsSyncHandler has already replaced
         * the optimistic negative-ID row with a real Room row keyed by smsRowId.
         * Fall back to messageId only if the extra is absent (legacy PendingIntent). */
        val smsRowId   = intent.getLongExtra(EXTRA_SMS_ROW_ID, -1L)
        val roomId     = if (smsRowId > 0L) smsRowId else messageId
        if (roomId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SMS_SENT -> {
                        val isSendOk = resultCode == Activity.RESULT_OK   // -1 = success
                        /*
                         * RESULT_CANCELED (0) is ambiguous — it fires when the PendingIntent
                         * is cancelled by the OS (e.g. process restart) rather than being a
                         * real send failure. Actual SmsManager error codes are all ≥ 1.
                         * Treating 0 as FAILED would cause a red-! flash even when the
                         * message goes through successfully.
                         */
                        val isKnownFailure = resultCode > 0
                        val label = when {
                            isSendOk      -> "SENT"
                            isKnownFailure -> "FAILED (resultCode=$resultCode)"
                            else           -> "AMBIGUOUS (resultCode=$resultCode) — leaving as PENDING"
                        }
                        syncLogger.log("SmsSentDelivery", "SMS_SENT roomId=$roomId smsRowId=$smsRowId result=$label")

                        when {
                            isSendOk -> {
                                messageRepository.updateDeliveryStatus(roomId, DELIVERY_STATUS_SENT)
                                /* Recovery: the radio confirmed this send, but the pre-send
                                 * insert into content://sms/sent never produced a row
                                 * (smsRowId == -1, e.g. a transient RemoteException or a
                                 * silently-revoked default-SMS-app role after an OS update).
                                 * Without this, the message is delivered to the recipient
                                 * but invisible to every reader of the system provider —
                                 * Postmark's own sync and companion apps like Phone Link. */
                                val address = intent.getStringExtra(EXTRA_ADDRESS)
                                if (shouldRecoverSentRow(isSendOk, smsRowId, address)) {
                                    recoverMissingSentRow(
                                        context, address!!,
                                        intent.getStringExtra(EXTRA_BODY) ?: ""
                                    )
                                }
                            }
                            isKnownFailure -> {
                                messageRepository.updateDeliveryStatus(roomId, DELIVERY_STATUS_FAILED)
                                /* Mirror failure status into content://sms so third-party apps
                                 * (e.g. Google Messages) don't keep showing the message as pending. */
                                if (smsRowId > 0L) {
                                    val cv = ContentValues().apply {
                                        put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_FAILED)
                                    }
                                    context.contentResolver.update(
                                        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, smsRowId),
                                        cv, null, null
                                    )
                                }
                            }
                            /* resultCode == 0: ambiguous — leave as PENDING so the delivery
                             * receipt or a future sync can confirm whether it went through. */
                        }
                    }
                    ACTION_SMS_DELIVERED -> {
                        syncLogger.log("SmsSentDelivery", "SMS_DELIVERED roomId=$roomId smsRowId=$smsRowId")
                        messageRepository.updateDeliveryStatus(roomId, DELIVERY_STATUS_DELIVERED)

                        // Mark delivery confirmed in the content provider.
                        if (smsRowId > 0L) {
                            val cv = ContentValues().apply {
                                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
                            }
                            context.contentResolver.update(
                                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, smsRowId),
                                cv, null, null
                            )
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Re-creates the `content://sms/sent` row for a radio-confirmed send whose
     * pre-send insert failed. Mirrors the ContentValues written by
     * [SmsManagerWrapper.sendTextMessage], except STATUS is [Telephony.Sms.STATUS_NONE]:
     * the delivery PendingIntent carries `smsRowId = -1` and can never reach this
     * recovered row, so no delivery report is pending on it.
     *
     * After inserting, awaits [SmsSyncHandler.triggerCatchUp] so the real Room row
     * exists (sync creates sent rows as PENDING awaiting this receiver — which has
     * already fired), then marks it SENT directly.
     */
    private suspend fun recoverMissingSentRow(context: Context, address: String, body: String) {
        val newRowId = try {
            val now = System.currentTimeMillis()
            // Same Samsung/MIUI mis-grouping guard as SmsManagerWrapper and SmsReceiver.
            val threadId = try {
                Telephony.Threads.getOrCreateThreadId(context, address)
            } catch (e: Exception) { 0L }
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.DATE_SENT, now)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
                if (threadId > 0L) put(Telephony.Sms.THREAD_ID, threadId)
            }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
                ?.let { ContentUris.parseId(it) } ?: -1L
        } catch (e: Exception) {
            syncLogger.logError("SmsSentDelivery", "recovery insert FAILED for address=$address — sent row remains missing", e)
            return
        }
        if (newRowId <= 0L) {
            syncLogger.logError("SmsSentDelivery", "recovery insert returned null uri for address=$address — sent row remains missing")
            return
        }
        syncLogger.log("SmsSentDelivery", "recovered missing sent row _id=$newRowId for address=$address")
        smsSyncHandler.triggerCatchUp()
        messageRepository.updateDeliveryStatus(newRowId, DELIVERY_STATUS_SENT)
    }

    companion object {
        const val ACTION_SMS_SENT      = "com.plusorminustwo.postmark.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.plusorminustwo.postmark.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID     = "message_id"
        // Real content-provider _id written to content://sms/sent at send time.
        const val EXTRA_SMS_ROW_ID     = "sms_row_id"
        /* Recovery payload (final part only): lets this receiver re-create the sent
         * row when the pre-send insert failed but the radio send succeeded. */
        const val EXTRA_ADDRESS        = "address"
        const val EXTRA_BODY           = "body"
    }
}

/**
 * True when the sent row must be re-created: the radio send succeeded but the
 * pre-send insert never produced a content-provider row, and the PendingIntent
 * carries the recovery address. Pure — unit-tested in SentRowRepairTest.
 */
internal fun shouldRecoverSentRow(sendSucceeded: Boolean, smsRowId: Long, address: String?): Boolean =
    sendSucceeded && smsRowId <= 0L && !address.isNullOrEmpty()
