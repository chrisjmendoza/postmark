package com.plusorminustwo.postmark.service.sms

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.plusorminustwo.postmark.PostmarkApplication
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles the "Reply" action on incoming SMS notifications.
 *
 * When the user types a reply directly in the notification shade, Android delivers
 * the text via [RemoteInput] to this receiver. The receiver:
 *  1. Extracts the reply text from the [RemoteInput] bundle.
 *  2. Sends it via [SmsManagerWrapper] (which attaches sent/delivered PendingIntents
 *     so delivery status is tracked the same way as in-app sends).
 *  3. Cancels the individual notification so it doesn't linger after the reply is sent.
 *  4. Cancels the group summary notification if no other SMS notifications remain.
 *
 * A negative message ID derived from the current time is used for the optimistic
 * send, matching the same convention as [ThreadViewModel][com.plusorminustwo.postmark.ui.thread.ThreadViewModel].
 * The [SmsContentObserver][com.plusorminustwo.postmark.data.sync.SmsContentObserver]
 * will sync the real sent message from the content provider once telephony confirms it.
 */
@AndroidEntryPoint
class DirectReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var smsManagerWrapper: SmsManagerWrapper

    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)

        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT_REPLY)
            ?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return

        smsManagerWrapper.sendTextMessage(address, replyText, -System.currentTimeMillis())

        val nm = context.getSystemService(NotificationManager::class.java)

        // ── Dismiss the individual thread notification ────────────────────────────
        nm.cancel(notifId)

        // ── Dismiss the group summary if no members remain ────────────────────────
        // After cancelling the individual notification, check whether any other SMS
        // notifications are still active. If none are, cancel the summary row too.
        val remaining = nm.activeNotifications.filter { sbn ->
            sbn.notification.group == PostmarkApplication.GROUP_KEY_SMS &&
                sbn.id != PostmarkApplication.NOTIF_ID_SMS_SUMMARY
        }
        if (remaining.isEmpty()) {
            nm.cancel(PostmarkApplication.NOTIF_ID_SMS_SUMMARY)
        }
    }

    companion object {
        const val KEY_TEXT_REPLY  = "key_text_reply"
        const val EXTRA_ADDRESS   = "extra_direct_reply_address"
        const val EXTRA_NOTIF_ID  = "extra_direct_reply_notif_id"
    }
}
