package com.plusorminustwo.postmark.service.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.data.repository.MessageRepository
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
 * The message ID is passed via [EXTRA_MESSAGE_ID] in the originating PendingIntent.
 */
@AndroidEntryPoint
class MmsSentReceiver : BroadcastReceiver() {

    @Inject lateinit var messageRepository: MessageRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_SENT) return
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId == -1L) return

        Log.d(TAG, "onReceive: resultCode=$resultCode  messageId=$messageId")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val status = if (resultCode == Activity.RESULT_OK) DELIVERY_STATUS_SENT
                             else DELIVERY_STATUS_FAILED
                messageRepository.updateDeliveryStatus(messageId, status)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MMS_SENT  = "com.plusorminustwo.postmark.MMS_SENT"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        private const val TAG      = "MmsSentReceiver"
    }
}
