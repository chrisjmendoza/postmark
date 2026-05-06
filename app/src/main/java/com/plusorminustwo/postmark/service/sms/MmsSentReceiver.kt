package com.plusorminustwo.postmark.service.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        val messageId  = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        val sentAtMs   = intent.getLongExtra(EXTRA_SENT_AT_MS, -1L)
        if (messageId == -1L) return

        val status = if (resultCode == Activity.RESULT_OK) DELIVERY_STATUS_SENT else DELIVERY_STATUS_FAILED
        val statusLabel = if (status == DELIVERY_STATUS_SENT) "SENT" else "FAILED (resultCode=$resultCode)"
        syncLogger.log(TAG, "MmsSentReceiver: messageId=$messageId sentAtMs=$sentAtMs result=$statusLabel")
        Log.i(TAG, "onReceive: resultCode=$resultCode  messageId=$messageId  status=$statusLabel")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // ── 1. Update the optimistic temp row (may already be deleted by sync) ──
                messageRepository.updateDeliveryStatus(messageId, status)

                // ── 2. Find and update the real content-provider MMS row ──────────────
                // Sync may have already replaced the temp row with the real row
                // (id = MMS_ID_OFFSET + rawMmsId). We find it by querying
                // content://mms/sent for rows within a 2-minute window of sentAtMs.
                // MMS dates are stored in seconds, sentAtMs is in milliseconds.
                if (sentAtMs > 0L) {
                    val sentAtSec   = sentAtMs / 1000
                    val windowStart = sentAtSec - 30      // 30 s before send (clock skew)
                    val windowEnd   = sentAtSec + 120     // 2 min after (slow carrier)

                    val cursor = context.contentResolver.query(
                        Uri.parse("content://mms/sent"),
                        arrayOf("_id"),
                        "date >= ? AND date <= ?",
                        arrayOf(windowStart.toString(), windowEnd.toString()),
                        "_id DESC"  // most recent first, in case of multiple candidates
                    )

                    cursor?.use { c ->
                        var updatedCount = 0
                        while (c.moveToNext()) {
                            val rawId  = c.getLong(0)
                            val roomId = MMS_ID_OFFSET + rawId
                            messageRepository.updateDeliveryStatus(roomId, status)
                            updatedCount++
                            // Only update the first (most recent) match to avoid stomping
                            // an older message's status.
                            break
                        }
                        if (updatedCount > 0) {
                            syncLogger.log(TAG, "MmsSentReceiver: updated real MMS row(s) to $statusLabel")
                        } else {
                            syncLogger.log(TAG, "MmsSentReceiver: no real MMS rows found in window [$windowStart, $windowEnd] — sync may not have run yet, temp row updated only")
                        }
                    } ?: syncLogger.log(TAG, "MmsSentReceiver: content://mms/sent cursor was null")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MMS_SENT  = "com.plusorminustwo.postmark.MMS_SENT"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        // Epoch-millis at which the optimistic message was created; used to find the
        // real content-provider row after the MMSC responds.
        const val EXTRA_SENT_AT_MS = "extra_sent_at_ms"
        private const val TAG      = "MmsSentReceiver"
    }
}
