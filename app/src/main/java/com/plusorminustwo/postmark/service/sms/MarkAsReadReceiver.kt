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
 * Handles the "Mark as read" action on incoming SMS notifications.
 *
 * Tapping the action button triggers this receiver, which:
 *  1. Updates [Telephony.Sms.CONTENT_URI] to set `read = 1` for all unread messages
 *     from the sender's [EXTRA_ADDRESS].
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
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        val notifId  = intent.getIntExtra(EXTRA_NOTIF_ID, 0)

        // ── Extend receiver lifetime ──────────────────────────────────────────────
        // goAsync() keeps the receiver alive past onReceive() so the ContentResolver
        // update (an I/O call) does not run on the main thread and risk an ANR.
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ── Mark messages as read in the telephony provider ───────────────
                // Updates all unread messages from this sender atomically.
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    ContentValues().apply { put(Telephony.Sms.READ, 1) },
                    "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.READ} = 0",
                    arrayOf(address)
                )
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
        const val EXTRA_ADDRESS  = "extra_mark_read_address"
        const val EXTRA_NOTIF_ID = "extra_mark_read_notif_id"
    }
}
