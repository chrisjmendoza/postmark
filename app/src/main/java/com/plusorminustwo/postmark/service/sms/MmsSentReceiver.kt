package com.plusorminustwo.postmark.service.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives the PendingIntent fired by [SmsManager.sendMultimediaMessage] when the MMSC
 * has accepted or rejected the outgoing MMS.
 *
 * Updates the Room delivery status to SENT (2) on success or FAILED (4) on error.
 *
 * On success it also persists the sent MMS into content://mms via
 * [MmsManagerWrapper.persistSentMms]. Postmark is the default SMS app, and Android only
 * auto-persists sent MMS for NON-default apps — so nothing writes the provider row unless
 * we do. Persisting it makes the send visible to other readers (Google Messages, Phone
 * Link) and gives [com.plusorminustwo.postmark.data.sync.SmsSyncHandler.syncLatestMms] a
 * real row, dated at the actual send time, to import and reconcile against the optimistic
 * Room row. A direct status update on the derived real Room id (MMS_ID_OFFSET + rawId)
 * closes the race where sync imports that freshly-persisted row before this receiver
 * finishes.
 *
 * (Replaces the earlier "search content://mms for a system-persisted row" loop, which
 * looked for a row that never exists for a default app and could latch onto an unrelated
 * RCS-archival row Google writes in the same id window.)
 */
@AndroidEntryPoint
class MmsSentReceiver : BroadcastReceiver() {

    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var mmsManagerWrapper: MmsManagerWrapper
    @Inject lateinit var syncLogger: SyncLogger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_SENT) return
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        val sentAtMs  = intent.getLongExtra(EXTRA_SENT_AT_MS, -1L)
        val toAddress = intent.getStringExtra(EXTRA_TO_ADDRESS)
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

                // ── 2. On success, persist the sent MMS into content://mms ourselves ───
                // (as the default SMS app, nothing else writes the provider row). On FAILED
                // there is nothing to persist — retrySend re-dispatches and a later success
                // persists then.
                if (status == DELIVERY_STATUS_SENT) {
                    val optimistic = messageRepository.getById(messageId)
                    when {
                        optimistic == null ->
                            // Already replaced/deleted; nothing recoverable (do NOT reconstruct from extras).
                            syncLogger.logError(TAG, "MmsSentReceiver: optimistic row messageId=$messageId already gone — cannot persist sent MMS")
                        toAddress.isNullOrEmpty() ->
                            syncLogger.logError(TAG, "MmsSentReceiver: missing toAddress — cannot persist sent MMS for messageId=$messageId")
                        else -> {
                            val rawId = mmsManagerWrapper.persistSentMms(
                                toAddress   = toAddress,
                                textBody    = optimistic.body,
                                attachments = optimistic.attachments,
                                messageId   = messageId,
                                sentAtMs    = sentAtMs
                            )
                            // Closes the race where sync imports the persisted row (transferring
                            // a stale PENDING) before this receiver finishes; updating a not-yet-
                            // imported Room id is a harmless no-op.
                            rawId?.let { messageRepository.updateDeliveryStatus(MMS_ID_OFFSET + it, status) }
                        }
                    }
                }
            } finally {
                // Delete the temp PDU file now that the platform has reported a result.
                // Doing this here (rather than on a 60 s timer in MmsManagerWrapper) ensures
                // the file is available for the full duration of any carrier retry / APN
                // bring-up, which can exceed 60 s on Samsung.
                // mms_attach_$messageId.bin is intentionally left in place — SmsSyncHandler
                // uses it as the attachmentUri for the real Room row so the image stays
                // visible after the optimistic row is replaced.
                try { java.io.File(context.cacheDir, "mms_out_$messageId.pdu").delete() } catch (_: Exception) {}
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MMS_SENT  = "com.plusorminustwo.postmark.MMS_SENT"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        /** Epoch-millis at which the optimistic message was created. [MmsManagerWrapper.persistSentMms]
         *  writes it (in seconds) as the provider row's date so sync restores send-time ordering. */
        const val EXTRA_SENT_AT_MS = "extra_sent_at_ms"
        /** Recipient address; [MmsManagerWrapper.persistSentMms] writes the canonical thread_id
         *  and the TO addr row from it. */
        const val EXTRA_TO_ADDRESS = "extra_to_address"
        private const val TAG      = "MmsSentReceiver"
    }
}
