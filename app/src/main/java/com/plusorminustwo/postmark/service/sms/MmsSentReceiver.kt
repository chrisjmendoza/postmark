package com.plusorminustwo.postmark.service.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.data.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Receives the [PendingIntent] callback fired by [SmsManager.sendMultimediaMessage]
 * and updates the message delivery status in the local database.
 *
 * Also cleans up the temporary PDU file written by [MmsManagerWrapper].
 */
@AndroidEntryPoint
class MmsSentReceiver : BroadcastReceiver() {

    @Inject lateinit var messageRepository: MessageRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MMS_SENT) return

        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId == -1L) return

        val pduFilePath = intent.getStringExtra(EXTRA_PDU_FILE)

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val status = if (resultCode == Activity.RESULT_OK)
                    DELIVERY_STATUS_SENT
                else
                    DELIVERY_STATUS_FAILED
                messageRepository.updateDeliveryStatus(messageId, status)
            } finally {
                pduFilePath?.let { File(it).delete() }
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MMS_SENT  = "com.plusorminustwo.postmark.MMS_SENT"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PDU_FILE   = "pdu_file"
    }
}
