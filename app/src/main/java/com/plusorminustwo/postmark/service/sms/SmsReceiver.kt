package com.plusorminustwo.postmark.service.sms

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.plusorminustwo.postmark.PostmarkApplication
import com.plusorminustwo.postmark.R
import com.plusorminustwo.postmark.data.preferences.PrivacyModeRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import com.plusorminustwo.postmark.data.sync.SyncLogger
import com.plusorminustwo.postmark.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject lateinit var syncHandler: SmsSyncHandler
    @Inject lateinit var threadRepository: ThreadRepository
    @Inject lateinit var privacyModeRepository: PrivacyModeRepository
    @Inject lateinit var syncLogger: SyncLogger

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    ?.takeIf { it.isNotEmpty() } ?: return

                // Parse PDU fields on the main thread — no IO, always fast.
                val isDeliver   = intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION
                val rawSender   = messages[0].originatingAddress ?: ""
                // Human-readable fallback used for notification display only.
                val sender      = rawSender.ifEmpty { "Unknown" }
                // Reconstruct full body from all PDU parts (multi-part SMS arrives as array).
                val body        = messages.joinToString("") { it.messageBody ?: "" }
                val timestampMs = messages[0].timestampMillis

                // Log broadcast receipt synchronously before goAsync() — if the process
                // is killed mid-async we still have a "broadcast arrived" entry in the log.
                syncLogger.log("SmsReceiver", if (isDeliver) "DELIVER_ACTION from=$rawSender" else "RECEIVED_ACTION from=$rawSender")

                // goAsync() extends the BroadcastReceiver lifetime so the OS does not
                // reclaim the process before our IO and notification work is done.
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // ── Persist to system SMS store (default SMS app only) ─────────
                        // SMS_DELIVER_ACTION fires exclusively for the default SMS app.
                        // We are solely responsible for writing the row to
                        // content://sms/inbox — the OS will NOT do it for us.
                        // All ContentResolver calls are done on Dispatchers.IO to avoid
                        // blocking the main thread (potential ANR).
                        if (isDeliver) {
                            persistToSystemStore(context, rawSender, body, timestampMs)
                        }
                        // SMS_RECEIVED_ACTION: another app is default and has already written
                        // the row. Do not insert again — would create a duplicate.

                        // Trigger Room incremental sync. The content observer will also fire
                        // once the row is written, but this explicit call guarantees Room
                        // is updated even if the observer notification is delayed or lost.
                        syncHandler.onSmsContentChanged(Telephony.Sms.CONTENT_URI)

                        // Check mute/notifications before posting the notification banner.
                        val notificationsEnabled =
                            threadRepository.isNotificationsEnabledByAddress(rawSender)
                        if (notificationsEnabled && !threadRepository.isMutedByAddress(rawSender)) {
                            // Look up the display name from ContactsContract first — it is
                            // always up-to-date even if the contact was added after the initial
                            // sync (which can leave a stale phone-number in Room's displayName).
                            // Fall back to the Room display name, then to the raw phone number.
                            val displayName = lookupContactName(context, rawSender)
                                ?: threadRepository.getDisplayNameByAddress(rawSender)
                                ?: sender
                            syncLogger.log("SmsReceiver", "notification: address=$rawSender displayName=$displayName")
                            postIncomingNotification(
                                context, displayName, body,
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

    // ── Persist to content://sms/inbox ────────────────────────────────────────
    // Writes one row for a newly received SMS. Sets THREAD_ID explicitly via
    // Telephony.Threads.getOrCreateThreadId() so OEM ROMs that don't auto-assign
    // it correctly don't end up creating duplicate threads for the same contact.
    // PROTOCOL = 0 distinguishes SMS from WAP push (= 1) for apps that filter by it.
    private fun persistToSystemStore(
        context: Context,
        rawSender: String,
        body: String,
        timestampMs: Long
    ) {
        // Resolve or create the canonical thread ID for this address.
        // Wrapped in try/catch because some OEMs throw on malformed addresses.
        val threadId: Long = try {
            if (rawSender.isNotEmpty())
                Telephony.Threads.getOrCreateThreadId(context, rawSender)
            else 0L
        } catch (e: Exception) {
            Log.w(TAG, "getOrCreateThreadId failed for sender=$rawSender", e)
            0L
        }

        val cv = ContentValues().apply {
            put(Telephony.Sms.Inbox.ADDRESS,   rawSender)
            put(Telephony.Sms.Inbox.BODY,      body)
            put(Telephony.Sms.Inbox.DATE,      System.currentTimeMillis())
            put(Telephony.Sms.Inbox.DATE_SENT, timestampMs)
            put(Telephony.Sms.Inbox.READ,      0)
            put(Telephony.Sms.Inbox.SEEN,      0)
            put(Telephony.Sms.PROTOCOL,        0)  // 0 = SMS, 1 = WAP push
            if (threadId > 0L) put(Telephony.Sms.THREAD_ID, threadId)
        }

        try {
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, cv)
            if (uri != null) {
                Log.d(TAG, "Wrote incoming SMS from=$rawSender → $uri")
                syncLogger.log("SmsReceiver", "wrote inbox row: from=$rawSender threadId=$threadId uri=$uri")
            } else {
                Log.e(TAG, "Insert to content://sms/inbox returned null for sender=$rawSender")
                syncLogger.logError("SmsReceiver", "Insert returned null for sender=$rawSender — message may be lost")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write incoming SMS to content://sms/inbox", e)
            syncLogger.logError("SmsReceiver", "Write to content://sms/inbox FAILED for sender=$rawSender", e)
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

    companion object {
        private const val TAG = "SmsReceiver"
    }

    // Queries ContactsContract.PhoneLookup for the display name of [address].
    // Returns null when the number has no matching contact. Uses the system's
    // built-in phone-number normalisation so "+12065550100" and "2065550100"
    // both resolve to the same contact entry.
    private fun lookupContactName(context: Context, address: String): String? {
        if (address.isEmpty()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                else null
            }
        } catch (_: Exception) { null }
    }
}
