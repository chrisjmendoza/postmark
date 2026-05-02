package com.plusorminustwo.postmark.service.sms

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
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
 *  3. Cancels the original notification so it doesn't linger after the reply is sent.
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
        context.getSystemService(NotificationManager::class.java).cancel(notifId)
    }

    companion object {
        const val KEY_TEXT_REPLY  = "key_text_reply"
        const val EXTRA_ADDRESS   = "extra_direct_reply_address"
        const val EXTRA_NOTIF_ID  = "extra_direct_reply_notif_id"
    }
}
