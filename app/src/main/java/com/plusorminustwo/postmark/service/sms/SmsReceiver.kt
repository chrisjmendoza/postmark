package com.plusorminustwo.postmark.service.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var syncHandler: SmsSyncHandler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                messages?.forEach { smsMessage ->
                    syncHandler.onSmsContentChanged(Telephony.Sms.CONTENT_URI)
                }
            }
        }
    }
}
