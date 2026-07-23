package com.plusorminustwo.postmark.service.sms

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.plusorminustwo.postmark.PostmarkApplication
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the "Report spam" action offered on incoming SMS notifications from unknown
 * (non-contact, non-group) senders.
 *
 * Tapping it:
 *  1. Sets `isSpam = 1` for the thread via [ThreadRepository.updateSpam] — the SAME DAO
 *     path the in-app "Report as spam" ⋮ action uses, so the thread hides into the Spam
 *     folder and its future notifications are suppressed. Never touches `content://sms`
 *     (no message is deleted — spam only hides + silences).
 *  2. Cancels the individual notification, and the group summary if it was the last one —
 *     mirrors [MarkAsReadReceiver]/[DirectReplyReceiver].
 *
 * There is no confirmation step (the notification action is itself the deliberate tap) and
 * nothing new is posted. Recovery is one tap away: Settings › Privacy › Spam → "Not spam".
 *
 * Dependencies come in via Hilt (`@AndroidEntryPoint` + field injection), the same pattern
 * as [DirectReplyReceiver].
 */
@AndroidEntryPoint
class ReportSpamReceiver : BroadcastReceiver() {

    @Inject lateinit var threadRepository: ThreadRepository

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        val notifId  = intent.getIntExtra(EXTRA_NOTIF_ID, 0)

        // goAsync() extends the receiver lifetime so the Room write runs on Dispatchers.IO
        // instead of the main thread — same pattern as the sibling receivers.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (threadId > 0L) threadRepository.updateSpam(threadId, true)
            } finally {
                val nm = context.getSystemService(NotificationManager::class.java)

                // ── Dismiss the individual thread notification ────────────────────
                nm.cancel(notifId)

                // ── Dismiss the group summary if no members remain ────────────────
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
        const val EXTRA_THREAD_ID = "extra_report_spam_thread_id"
        const val EXTRA_NOTIF_ID  = "extra_report_spam_notif_id"
    }
}
