package com.plusorminustwo.postmark.service.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.data.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmsSentDeliveryReceiver : BroadcastReceiver() {

    @Inject lateinit var messageRepository: MessageRepository

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SMS_SENT -> {
                        val status = if (resultCode == Activity.RESULT_OK)
                            DELIVERY_STATUS_SENT
                        else
                            DELIVERY_STATUS_FAILED
                        messageRepository.updateDeliveryStatus(messageId, status)
                    }
                    ACTION_SMS_DELIVERED ->
                        messageRepository.updateDeliveryStatus(messageId, DELIVERY_STATUS_DELIVERED)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SMS_SENT = "com.plusorminustwo.postmark.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.plusorminustwo.postmark.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
    }
}
