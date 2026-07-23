package com.plusorminustwo.postmark.service.sms

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ThumbnailUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.plusorminustwo.postmark.PostmarkApplication
import com.plusorminustwo.postmark.R
import com.plusorminustwo.postmark.data.contacts.loadContactPhotoBitmap
import com.plusorminustwo.postmark.ui.MainActivity
import com.plusorminustwo.postmark.ui.components.avatarColor
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
                putExtra(MarkAsReadReceiver.EXTRA_THREAD_ID, threadId)
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setGroup(PostmarkApplication.GROUP_KEY_SMS)

        if (privacyMode) {
            // Privacy mode keeps the plain BigTextStyle: no Person, no avatar, and no
            // long-lived conversation shortcut. A shortcut carrying the sender's name and
            // photo would persist the very identity privacy mode is redacting.
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
        } else {
            // Group threads omit the inline reply — it could only reach one participant.
            if (allowDirectReply) builder.addAction(replyAction)
            builder.addAction(markReadAction)

            // ── Conversation (MessagingStyle) rendering ───────────────────────────
            // A MessagingStyle notification tied to a long-lived conversation shortcut is
            // what the OS (and OneUI) promotes to the Conversations section and renders with
            // the sender's photo LARGE on the left plus the app icon as a small badge — the
            // Google Messages look. The old BigTextStyle + setLargeIcon rendered the avatar
            // on the right with the app icon filling the left slot.
            val icon = IconCompat.createWithBitmap(senderAvatar(address, title))

            // Group titles arrive composed as "Sender — Group name" (the caller owns that
            // format). Split so the sender becomes the Person and the group name becomes the
            // conversation title; a 1:1 title is the sender name verbatim. The split couples
            // to the caller's separator — an acceptable, contained cost versus reworking both
            // call-site contracts to pass sender and group name as separate fields.
            val senderName: String
            val conversationTitle: String?
            if (!allowDirectReply) {
                val parts = displayTitle.split(GROUP_TITLE_SEPARATOR, limit = 2)
                senderName = parts.first()
                conversationTitle = parts.getOrNull(1)
            } else {
                senderName = displayTitle
                conversationTitle = null
            }

            val person = Person.Builder()
                .setName(senderName)
                .setIcon(icon)
                // Per-sender identity: the sender's address distinguishes participants in a
                // group and stays stable for a 1:1; falls back to the conversation key.
                .setKey(address.ifEmpty { notifKey.toString() })
                .build()

            val style = NotificationCompat.MessagingStyle(person)
            // Carry the messages already showing under this key forward so successive
            // messages stack in one conversation with a count (Google Messages' "2") instead
            // of each replacing the last. Capped so a long-lived thread can't grow unbounded.
            nm.activeNotifications
                .firstOrNull { it.id == notifKey }
                ?.notification
                ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
                ?.messages
                ?.takeLast(MAX_CARRIED_MESSAGES - 1)
                ?.forEach { style.addMessage(it.text, it.timestamp, it.person) }
            style.addMessage(displayBody, System.currentTimeMillis(), person)
            if (!allowDirectReply) {
                style.isGroupConversation = true
                style.conversationTitle = conversationTitle
            }
            builder.setStyle(style)

            // ── Long-lived conversation shortcut ──────────────────────────────────
            // Required for the avatar-left conversation treatment on many OEMs. Stable per
            // conversation so repeat messages refresh one shortcut. Uses an ACTION_VIEW deep
            // link to the same thread the content intent opens (shortcut intents require an
            // action).
            val shortcutId = if (threadId > 0L) "thread_$threadId" else "notif_$notifKey"
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                .setLongLived(true)
                .setPerson(person)
                .setIcon(icon)
                .setShortLabel(senderName)
                .setIntent(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        if (threadId > 0L) putExtra(MainActivity.EXTRA_OPEN_THREAD_ID, threadId)
                    }
                )
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            builder.setShortcutId(shortcutId)
        }

        nm.notify(notifKey, builder.build())

        // ── Summary notification ──────────────────────────────────────────────────
        updateSummaryNotification(nm)
    }

    /**
     * The circular avatar shown as the notification's large icon: the contact's photo when
     * they have one, otherwise the same letter-on-color avatar the conversation list draws.
     *
     * Falling back to the letter avatar rather than to nothing is deliberate — a null large
     * icon leaves the launcher icon in its place, so every notification from an unsaved
     * number would look identical.
     *
     * Cropped to a circle here rather than relying on the system: notification large icons
     * are only shape-masked on newer platforms, and [IconCompat.createWithAdaptiveBitmap]
     * would zoom into the adaptive-icon safe zone and cut off the edges of a face.
     *
     * Blocking IO (photo decode) — [notify] is called from Dispatchers.IO on both paths.
     */
    private fun senderAvatar(address: String, displayName: String): Bitmap {
        val size = context.resources.getDimensionPixelSize(
            android.R.dimen.notification_large_icon_width
        )
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val radius = size / 2f
        val photo = context.loadContactPhotoBitmap(address)

        if (photo != null) {
            // extractThumbnail center-crops, so a non-square photo isn't squashed.
            val square = ThumbnailUtils.extractThumbnail(photo, size, size)
            canvas.drawCircle(radius, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            })
        } else {
            // Seeded on address, matching ContactAvatar — the same contact gets the same
            // color in the notification and in the app.
            canvas.drawCircle(radius, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = avatarColor(address).toArgb()
            })
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = size * 0.4f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            val letter = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            // Center the glyph on its own metrics, not on the text origin (a baseline at
            // the circle's midpoint would sit visibly low).
            val baseline = radius - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(letter, radius, baseline, textPaint)
        }
        return output
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
            val n = sbn.notification
            // MessagingStyle notifications store their content in EXTRA_MESSAGES /
            // EXTRA_CONVERSATION_TITLE, not EXTRA_TITLE/EXTRA_TEXT (which can be null), so
            // read the conversation/sender name and latest message text back out of the
            // MessagingStyle. Privacy-mode notifications are still plain BigTextStyle, so
            // fall back to the legacy extras when no MessagingStyle is present.
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            val title: CharSequence
            val text: CharSequence
            if (style != null) {
                val last = style.messages.lastOrNull()
                title = style.conversationTitle
                    ?: last?.person?.name
                    ?: n.extras.getString(Notification.EXTRA_TITLE) ?: ""
                text = last?.text ?: n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
            } else {
                title = n.extras.getString(Notification.EXTRA_TITLE) ?: ""
                text  = n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
            }
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

    private companion object {
        /** Ceiling on how many messages one accumulated conversation notification retains. */
        const val MAX_CARRIED_MESSAGES = 8

        /**
         * The separator the callers use to compose a group title as "Sender — Group name"
         * (see SmsSyncHandler.notifyIncomingMms). Kept in sync by hand — the callers live in
         * data/ and own the format; IncomingNotifier only reverses it for display.
         */
        const val GROUP_TITLE_SEPARATOR = " — "
    }
}
