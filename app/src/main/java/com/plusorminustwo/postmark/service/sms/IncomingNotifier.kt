package com.plusorminustwo.postmark.service.sms

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.plusorminustwo.postmark.PostmarkApplication
import com.plusorminustwo.postmark.R
import com.plusorminustwo.postmark.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts the incoming-message notification.
 *
 * Extracted out of [SmsReceiver] (GROUP_MESSAGING_SPEC §4.1) so both SMS ([SmsReceiver])
 * and MMS ([com.plusorminustwo.postmark.data.sync.SmsSyncHandler] — MMS arrivals
 * previously posted no notification at all) share one builder, one channel, one
 * grouping/summary scheme, and one privacy-mode redaction path instead of maintaining
 * two copies that could drift.
 */
@Singleton
class IncomingNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * @param notifKey    stable per-conversation key. Callers use `address.hashCode()` for
     *                    1:1 conversations (so an SMS and a 1:1 MMS from the same contact
     *                    collapse into the same notification) and `threadId.hashCode()` for
     *                    group MMS threads, which have no single stable address.
     * @param threadId    canonical telephony thread id for the deep-link (Room thread ids
     *                    ARE telephony thread ids in this codebase); -1 falls back to
     *                    opening the conversation list.
     * @param address     the raw phone number to reply to — only read when
     *                    [allowDirectReply] is true.
     * @param title       notification title before privacy redaction — plain contact name
     *                    for 1:1, "Sender — Group name" for group threads (caller composes it).
     * @param allowDirectReply false for group threads — the inline reply action is omitted
     *                    because a single-address reply can't reach the whole group.
     */
    fun notify(
        notifKey: Int,
        threadId: Long,
        address: String,
        title: String,
        body: String,
        privacyMode: Boolean,
        allowDirectReply: Boolean
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)

        // ── Privacy mode: redact sender and body so bystanders can't read the screen ──
        val displayTitle = if (privacyMode) context.getString(R.string.privacy_mode_notification_title) else title
        val displayBody  = if (privacyMode) "" else body

        // ── Content intent — deep-links straight to this conversation ─────────────
        val openIntent = PendingIntent.getActivity(
            context,
            if (threadId > 0L) threadId.toInt() else 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (threadId > 0L) putExtra(MainActivity.EXTRA_OPEN_THREAD_ID, threadId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Reply action ──────────────────────────────────────────────────────────
        val remoteInput = RemoteInput.Builder(DirectReplyReceiver.KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.reply))
            .build()

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notifKey xor 0x0100_0000,
            Intent(context, DirectReplyReceiver::class.java).apply {
                putExtra(DirectReplyReceiver.EXTRA_ADDRESS, address)
                putExtra(DirectReplyReceiver.EXTRA_NOTIF_ID, notifKey)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.reply),
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        // ── Mark as read action ───────────────────────────────────────────────────
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notifKey xor 0x0200_0000,
            Intent(context, MarkAsReadReceiver::class.java).apply {
                putExtra(MarkAsReadReceiver.EXTRA_ADDRESS, address)
                putExtra(MarkAsReadReceiver.EXTRA_NOTIF_ID, notifKey)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.mark_as_read),
            markReadPendingIntent
        ).build()

        // ── Individual per-thread notification ────────────────────────────────────
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
            // Group threads omit the inline reply — it could only reach one participant.
            if (allowDirectReply) builder.addAction(replyAction)
            builder.addAction(markReadAction)
        }

        nm.notify(notifKey, builder.build())

        // ── Summary notification ──────────────────────────────────────────────────
        updateSummaryNotification(nm)
    }

    /**
     * Posts or refreshes the InboxStyle summary notification that heads the SMS/MMS group.
     *
     * Reads the currently active notifications in the [PostmarkApplication.GROUP_KEY_SMS]
     * group (excluding the summary itself), builds one line per thread, and posts a
     * summary with [NotificationCompat.InboxStyle].
     *
     * If no group members remain (e.g. all were dismissed), the summary is cancelled.
     */
    private fun updateSummaryNotification(nm: NotificationManager) {
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
