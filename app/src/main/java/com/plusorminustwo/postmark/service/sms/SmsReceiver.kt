package com.plusorminustwo.postmark.service.sms

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
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
        val notifId = sender.hashCode()

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // RemoteInput lets the user type a reply directly in the notification shade.
        // FLAG_MUTABLE is required so the system can inject the typed text into the
        // PendingIntent extras before delivering it to DirectReplyReceiver.
        val remoteInput = RemoteInput.Builder(DirectReplyReceiver.KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.reply))
            .build()

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId xor 0x0100_0000,
            Intent(context, DirectReplyReceiver::class.java).apply {
                putExtra(DirectReplyReceiver.EXTRA_ADDRESS, sender)
                putExtra(DirectReplyReceiver.EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.reply),
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        // ── "Mark as read" action ─────────────────────────────────────────────────
        // A distinct request code (xor 0x0200_0000) avoids colliding with the reply
        // PendingIntent that uses 0x0100_0000. FLAG_IMMUTABLE is safe here because
        // no dynamic data needs to be injected into this intent by the system.
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId xor 0x0200_0000,
            Intent(context, MarkAsReadReceiver::class.java).apply {
                putExtra(MarkAsReadReceiver.EXTRA_ADDRESS, sender)
                putExtra(MarkAsReadReceiver.EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.mark_as_read),
            markReadPendingIntent
        ).build()

        val notification = NotificationCompat.Builder(context, PostmarkApplication.CHANNEL_INCOMING_SMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sender)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(replyAction)
            .addAction(markReadAction)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(notifId, notification)
    }
}
