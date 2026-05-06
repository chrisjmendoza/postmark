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
import com.plusorminustwo.postmark.data.sync.SyncLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmsSentDeliveryReceiver : BroadcastReceiver() {

    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var syncLogger: SyncLogger

    override fun onReceive(context: Context, intent: Intent) {
        val messageId  = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        // smsRowId is the positive content-provider _id captured at insert time in
        // SmsManagerWrapper.  By the time this fires, SmsSyncHandler has already
        // replaced the optimistic negative-ID row with a Room row keyed by smsRowId.
        // Fall back to messageId only if the extra is absent (e.g. legacy PendingIntent).
        val smsRowId   = intent.getLongExtra(EXTRA_SMS_ROW_ID, -1L)
        val roomId     = if (smsRowId > 0L) smsRowId else messageId
        if (roomId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SMS_SENT -> {
                        val isSendOk = resultCode == Activity.RESULT_OK   // -1 = success
                        // RESULT_CANCELED (0) is ambiguous: it fires when the PendingIntent is
                        // canceled by the OS (e.g. process restart) rather than an actual send
                        // failure. Real SmsManager error codes are all ≥ 1. Treating 0 as FAILED
                        // produces a red ! flash even when messages successfully deliver.
                        val isKnownFailure = resultCode > 0
                        val label = when {
                            isSendOk      -> "SENT"
                            isKnownFailure -> "FAILED (resultCode=$resultCode)"
                            else           -> "AMBIGUOUS (resultCode=$resultCode) — leaving as PENDING"
                        }
                        syncLogger.log("SmsSentDelivery", "SMS_SENT roomId=$roomId smsRowId=$smsRowId result=$label")

                        when {
                            isSendOk -> messageRepository.updateDeliveryStatus(roomId, DELIVERY_STATUS_SENT)
                            isKnownFailure -> {
                                messageRepository.updateDeliveryStatus(roomId, DELIVERY_STATUS_FAILED)
                                // Mirror the failure into content://sms so third-party apps
                                // (e.g. Google Messages) don't keep showing the message as pending.
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
                            // resultCode == 0: leave status as PENDING; the delivery receipt or
                            // a future sync will update it if the message went through.
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

    companion object {
        const val ACTION_SMS_SENT      = "com.plusorminustwo.postmark.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.plusorminustwo.postmark.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID     = "message_id"
        // Real content-provider _id written to content://sms/sent at send time.
        const val EXTRA_SMS_ROW_ID     = "sms_row_id"
    }
}
