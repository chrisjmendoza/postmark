package com.plusorminustwo.postmark.service.sms

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.plusorminustwo.postmark.PostmarkApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the "Mark as read" action on incoming SMS/MMS notifications.
 *
 * Tapping the action button triggers this receiver, which:
 *  1. Marks unread rows read in both [Telephony.Sms.CONTENT_URI] and
 *     [Telephony.Mms.CONTENT_URI], scoped to [EXTRA_THREAD_ID] when available (see
 *     [ConversationReadMarker]) — this covers MMS (group messages, media), which have
 *     no single stable sender address to filter on. Falls back to the address-scoped
 *     SMS-only update when no thread id was passed (older/edge-case notifications).
 *  2. Cancels the individual notification so it disappears from the shade.
 *  3. Cancels the group summary notification if no other SMS notifications remain.
 *
 * No Room interaction is needed — [com.plusorminustwo.postmark.data.sync.SmsContentObserver]
 * will pick up the provider change and update the local database via the normal sync path.
 *
 * Requires WRITE_SMS (already declared in AndroidManifest).
 */
class MarkAsReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val address  = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        val notifId  = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)

        // ── Extend receiver lifetime ──────────────────────────────────────────────
        // goAsync() keeps the receiver alive past onReceive() so the ContentResolver
        // update (an I/O call) does not run on the main thread and risk an ANR.
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ── Mark messages as read in the telephony provider(s) ────────────
                // Each provider update is wrapped independently so a failure in one
                // (e.g. MMS write rejected) can't skip the other or the notification
                // cancel below.
                ConversationReadMarker.buildUpdates(threadId, address).forEach { update ->
                    try {
                        val uri = when (update.table) {
                            ConversationReadMarker.Table.SMS -> Telephony.Sms.CONTENT_URI
                            ConversationReadMarker.Table.MMS -> Telephony.Mms.CONTENT_URI
                        }
                        context.contentResolver.update(
                            uri,
                            ContentValues().apply { put(Telephony.Sms.READ, 1) }, // "read" — same column name in both providers
                            update.selection,
                            update.selectionArgs.toTypedArray()
                        )
                    } catch (e: Exception) {
                        // Best-effort: one provider failing must not prevent the other
                        // update or the notification dismissal in `finally` below.
                    }
                }
            } finally {
                val nm = context.getSystemService(NotificationManager::class.java)

                // ── Dismiss the individual thread notification ────────────────────
                nm.cancel(notifId)

                // ── Dismiss the group summary if no members remain ────────────────
                // After cancelling the individual notification, check whether any
                // other SMS notifications are still active. If none are, the summary
                // row would be an empty group, so cancel it too.
                val remaining = nm.activeNotifications.filter { sbn ->
                    sbn.notification.group == PostmarkApplication.GROUP_KEY_SMS &&
                        sbn.id != PostmarkApplication.NOTIF_ID_SMS_SUMMARY
                }
                if (remaining.isEmpty()) {
                    nm.cancel(PostmarkApplication.NOTIF_ID_SMS_SUMMARY)
                }

                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ADDRESS   = "extra_mark_read_address"
        const val EXTRA_NOTIF_ID  = "extra_mark_read_notif_id"
        const val EXTRA_THREAD_ID = "extra_mark_read_thread_id"
    }
}
