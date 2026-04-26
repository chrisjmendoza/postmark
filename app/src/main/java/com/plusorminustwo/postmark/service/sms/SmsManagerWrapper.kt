package com.plusorminustwo.postmark.service.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsManagerWrapper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val smsManager: SmsManager
        get() = context.getSystemService(SmsManager::class.java)

    fun sendTextMessage(destinationAddress: String, text: String, messageId: Long) {
        val reqBase = (messageId and 0x3FFF_FFFFL).toInt()

        fun sentIntent(offset: Int = 0) = PendingIntent.getBroadcast(
            context,
            reqBase + offset,
            Intent(context, SmsSentDeliveryReceiver::class.java).apply {
                action = SmsSentDeliveryReceiver.ACTION_SMS_SENT
                putExtra(SmsSentDeliveryReceiver.EXTRA_MESSAGE_ID, messageId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )

        fun deliveredIntent() = PendingIntent.getBroadcast(
            context,
            reqBase + 0x4000,
            Intent(context, SmsSentDeliveryReceiver::class.java).apply {
                action = SmsSentDeliveryReceiver.ACTION_SMS_DELIVERED
                putExtra(SmsSentDeliveryReceiver.EXTRA_MESSAGE_ID, messageId)
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
                sentIntents.add(sentIntent(i))
                deliveredIntents.add(if (i == parts.lastIndex) deliveredIntent() else null)
            }
            smsManager.sendMultipartTextMessage(
                destinationAddress, null, parts, sentIntents, deliveredIntents
            )
        }
    }
}
