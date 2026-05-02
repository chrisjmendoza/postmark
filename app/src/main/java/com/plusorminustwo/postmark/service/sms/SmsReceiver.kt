package com.plusorminustwo.postmark.service.sms

import android.app.Notification
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
import com.plusorminustwo.postmark.data.preferences.PrivacyModeRepository
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

    @Inject
    lateinit var privacyModeRepository: PrivacyModeRepository

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
                            postIncomingNotification(
                                context, sender, body,
                                privacyMode = privacyModeRepository.isEnabled()
                            )
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun postIncomingNotification(context: Context, sender: String, body: String, privacyMode: Boolean) {
        val notifId = sender.hashCode()
        val nm = context.getSystemService(NotificationManager::class.java)

        // ── Privacy mode: redact sender and body so bystanders can't read the screen ──
        val displayTitle = if (privacyMode) context.getString(R.string.privacy_mode_notification_title) else sender
        val displayBody  = if (privacyMode) "" else body

        // ── Content intent — opens the conversation list ──────────────────────────
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Reply action ──────────────────────────────────────────────────────────
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

        // ── Mark as read action ───────────────────────────────────────────────────
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

        // ── Individual per-thread notification ────────────────────────────────────
        // setGroup() registers this notification with the shared SMS bundle so
        // Android can collapse multiple thread notifications in the shade.
        // When privacy mode is on, actions are omitted so the reply RemoteInput
        // can't be used to discover who the sender was.
        val builder = NotificationCompat.Builder(context, PostmarkApplication.CHANNEL_INCOMING_SMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setGroup(PostmarkApplication.GROUP_KEY_SMS)

        if (!privacyMode) {
            builder.addAction(replyAction).addAction(markReadAction)
        }

        nm.notify(notifId, builder.build())

        // ── Summary notification ──────────────────────────────────────────────────
        // Must be posted (or refreshed) after every individual notification so
        // Android can display the collapsed group row on API 24+ devices.
        updateSummaryNotification(context, nm)
    }

    /**
     * Posts or refreshes the InboxStyle summary notification that heads the SMS group.
     *
     * Reads the currently active notifications in the [PostmarkApplication.GROUP_KEY_SMS]
     * group (excluding the summary itself), builds one line per thread, and posts a
     * summary with [NotificationCompat.InboxStyle].
     *
     * If no group members remain (e.g. all were dismissed), the summary is cancelled.
     */
    private fun updateSummaryNotification(context: Context, nm: NotificationManager) {
        // ── Count active group members ────────────────────────────────────────────
        // activeNotifications is available from API 23; minSdk = 26 so always safe.
        val groupNotifs = nm.activeNotifications.filter { sbn ->
            sbn.notification.group == PostmarkApplication.GROUP_KEY_SMS &&
                sbn.id != PostmarkApplication.NOTIF_ID_SMS_SUMMARY
        }

        if (groupNotifs.isEmpty()) {
            nm.cancel(PostmarkApplication.NOTIF_ID_SMS_SUMMARY)
            return
        }

        val count = groupNotifs.size
        val summaryText = context.resources.getQuantityString(
            R.plurals.notification_summary_new_messages, count, count
        )

        // ── Build InboxStyle lines: "Sender  preview" per thread ─────────────────
        val inboxStyle = NotificationCompat.InboxStyle()
            .setSummaryText(context.getString(R.string.app_name))
        groupNotifs.forEach { sbn ->
            val title = sbn.notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text  = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
            inboxStyle.addLine(if (title.isNotEmpty()) "$title  $text" else "$text")
        }

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summary = NotificationCompat.Builder(context, PostmarkApplication.CHANNEL_INCOMING_SMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(summaryText)
            .setContentText(summaryText)
            .setStyle(inboxStyle)
            .setGroup(PostmarkApplication.GROUP_KEY_SMS)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        nm.notify(PostmarkApplication.NOTIF_ID_SMS_SUMMARY, summary)
    }
}
