package com.plusorminustwo.postmark.service.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.sync.SyncLogger
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives the PendingIntent fired by [SmsManager.sendMultimediaMessage] when the MMSC
 * has accepted or rejected the outgoing MMS.
 *
 * Updates the Room delivery status to SENT (2) on success or FAILED (4) on error.
 *
 * Two Room rows are updated to handle a race with [SmsSyncHandler.syncLatestMms]:
 *  1. The temp negative-ID optimistic row (in case sync hasn't run yet).
 *  2. The real Room row derived from the content://mms/_id (in case sync already
 *     replaced the temp row before this receiver fired).
 *
 * The real MMS row is found by querying content://mms/sent for rows whose date
 * falls within 2 minutes of when the optimistic message was created, using
 * [EXTRA_SENT_AT_MS] to reconstruct the send timestamp.
 */
@AndroidEntryPoint
class MmsSentReceiver : BroadcastReceiver() {

    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var syncLogger: SyncLogger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_SENT) return
        val messageId       = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        val sentAtMs        = intent.getLongExtra(EXTRA_SENT_AT_MS, -1L)
        val beforeSendMaxId = intent.getLongExtra(EXTRA_BEFORE_SEND_MAX_ID, -1L)
        if (messageId == -1L) return

        val status = if (resultCode == Activity.RESULT_OK) DELIVERY_STATUS_SENT else DELIVERY_STATUS_FAILED
        // Log the human-readable MMS error name alongside the raw resultCode and any
        // HTTP status (EXTRA_MMS_HTTP_STATUS is populated when the MMSC returns an HTTP error).
        val errorName = when (resultCode) {
            Activity.RESULT_OK                    -> "RESULT_OK"
            SmsManager.MMS_ERROR_UNSPECIFIED      -> "MMS_ERROR_UNSPECIFIED"
            SmsManager.MMS_ERROR_INVALID_APN      -> "MMS_ERROR_INVALID_APN"
            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "MMS_ERROR_UNABLE_CONNECT_MMS"
            SmsManager.MMS_ERROR_HTTP_FAILURE     -> "MMS_ERROR_HTTP_FAILURE"
            SmsManager.MMS_ERROR_IO_ERROR         -> "MMS_ERROR_IO_ERROR"
            SmsManager.MMS_ERROR_RETRY            -> "MMS_ERROR_RETRY"
            SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "MMS_ERROR_CONFIGURATION_ERROR"
            SmsManager.MMS_ERROR_NO_DATA_NETWORK  -> "MMS_ERROR_NO_DATA_NETWORK"
            else                                  -> "UNKNOWN($resultCode)"
        }
        val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, -1)
        val httpLabel  = if (httpStatus != -1) " httpStatus=$httpStatus" else ""
        val statusLabel = if (status == DELIVERY_STATUS_SENT) "SENT" else "FAILED $errorName$httpLabel"
        syncLogger.log(TAG, "MmsSentReceiver: messageId=$messageId sentAtMs=$sentAtMs result=$statusLabel")
        Log.i(TAG, "onReceive: resultCode=$resultCode  messageId=$messageId  status=$statusLabel")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // ── 1. Update the optimistic temp row (may already be deleted by sync) ──
                messageRepository.updateDeliveryStatus(messageId, status)

                // ── 2. Find and update the real content-provider MMS row ──────────────
                // Sync may have already replaced the temp row with the real row
                // (id = MMS_ID_OFFSET + rawMmsId). We find it by querying the raw
                // content://mms table (NOT the /sent view) filtered by msg_box IN (2=sent,
                // 4=outbox) and a date window. The raw table is written by the system MMS
                // service before the /sent view is updated, so this is more reliable.
                // We retry up to 4 times with 3 s gaps in case the row hasn't been
                // written yet; on persistent failure SmsSyncHandler will pick it up.
                /* Find the real content://mms row and update its delivery status.
                 * Primary strategy: query _id > beforeSendMaxId (passed in from ThreadViewModel
                 * right before the send). This is immune to the date-field format ambiguity
                 * (some devices store date in seconds, others in milliseconds).
                 * Fallback: date window based on sentAtMs for older PendingIntents. */
                val useIdQuery = beforeSendMaxId >= 0L

                var updatedCount = 0
                var attempt      = 0
                while (updatedCount == 0 && attempt < 4) {
                    if (attempt > 0) delay(3_000)
                    attempt++

                    val cursor = if (useIdQuery) {
                        // Reliable: find any sent/outbox row written after the snapshot taken
                        // immediately before sendMultimediaMessage was called.
                        context.contentResolver.query(
                            Uri.parse("content://mms"),
                            arrayOf("_id"),
                            "_id > ? AND (msg_box = 2 OR msg_box = 4)",
                            arrayOf(beforeSendMaxId.toString()),
                            "_id ASC"
                        )
                    } else {
                        // Legacy fallback for PendingIntents created before EXTRA_BEFORE_SEND_MAX_ID.
                        // Note: date field is seconds on stock Android but ms on some OEMs.
                        val sentAtSec = sentAtMs / 1000
                        context.contentResolver.query(
                            Uri.parse("content://mms"),
                            arrayOf("_id"),
                            "date >= ? AND date <= ? AND (msg_box = 2 OR msg_box = 4)",
                            arrayOf((sentAtSec - 30).toString(), (sentAtSec + 120).toString()),
                            "_id DESC"
                        )
                    }

                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val rawId  = c.getLong(0)
                            val roomId = MMS_ID_OFFSET + rawId
                            messageRepository.updateDeliveryStatus(roomId, status)
                            updatedCount++
                            syncLogger.log(TAG, "MmsSentReceiver: updated real MMS row to $statusLabel (attempt $attempt, rawId=$rawId)")
                        }
                    } ?: syncLogger.log(TAG, "MmsSentReceiver: content://mms cursor was null (attempt $attempt)")
                }

                if (updatedCount == 0) {
                    syncLogger.log(TAG, "MmsSentReceiver: no real MMS rows found after $attempt attempts — SmsSyncHandler will transfer status on next sync")
                }
            } finally {
                /* Leave mms_attach_$messageId.bin in place \u2014 SmsSyncHandler uses it
                 * as the attachmentUri for the real Room row so the image stays visible
                 * after the optimistic row is replaced. Cleanup is handled on app start. */
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MMS_SENT         = "com.plusorminustwo.postmark.MMS_SENT"
        const val EXTRA_MESSAGE_ID        = "extra_message_id"
        /** Epoch-millis at which the optimistic message was created. */
        const val EXTRA_SENT_AT_MS        = "extra_sent_at_ms"
        /** Max content://mms _id snapshot taken immediately before sendMultimediaMessage.
         *  Used to find the real row without relying on the date field format. */
        const val EXTRA_BEFORE_SEND_MAX_ID = "extra_before_send_max_mms_id"
        private const val TAG             = "MmsSentReceiver"
    }
}
