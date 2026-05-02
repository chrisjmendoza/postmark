package com.plusorminustwo.postmark.service.sms

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.plusorminustwo.postmark.PostmarkApplication
import com.plusorminustwo.postmark.R
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import com.plusorminustwo.postmark.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var syncHandler: SmsSyncHandler

    @Inject
    lateinit var threadRepository: ThreadRepository

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    ?.takeIf { it.isNotEmpty() } ?: return

                // Sync once — multi-part messages share the same thread entry
                syncHandler.onSmsContentChanged(Telephony.Sms.CONTENT_URI)

                // Reconstruct full body from all parts (multi-part SMS arrives as array)
                val sender = messages[0].originatingAddress ?: "Unknown"
                val body = messages.joinToString("") { it.messageBody ?: "" }

                // Check mute asynchronously before posting. goAsync() keeps the receiver
                // alive until finish() is called so the OS does not reclaim the process.
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (!threadRepository.isMutedByAddress(sender)) {
                            postIncomingNotification(context, sender, body)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun postIncomingNotification(context: Context, sender: String, body: String) {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, PostmarkApplication.CHANNEL_INCOMING_SMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sender)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(sender.hashCode(), notification)
    }
}
