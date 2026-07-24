package com.plusorminustwo.postmark.service.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.plusorminustwo.postmark.PostmarkApplication
import com.plusorminustwo.postmark.R
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.model.previewText
import com.plusorminustwo.postmark.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Fires a user-set reply reminder ("Remind me" on a message): posts a notification that
 * deep-links back to the flagged message.
 *
 * Scheduling is a plain [OneTimeWorkRequest] with an [setInitialDelay] equal to the wait
 * until the chosen time — deliberately NOT an exact alarm (the app keeps zero exact-alarm
 * surface; see docs/TARGET_SDK_REVIEW.md). Minute-level inexactness is fine for a reply
 * reminder, and WorkManager persists its queue across reboots for free.
 *
 * Unique work name is `message_reminder_<messageId>` with [ExistingWorkPolicy.REPLACE], so
 * re-flagging a message reschedules cleanly and [cancel] (on unflag) removes it.
 *
 * On fire the worker re-reads the message and posts only if it is still flagged
 * ([com.plusorminustwo.postmark.domain.model.Message.remindAt] non-null) — covering the race
 * where the user unflagged between scheduling and firing. It deliberately LEAVES remindAt set:
 * the flag persists after the reminder fires (a past remindAt renders the same indicator and
 * still appears in the per-thread reminders list) until the user clears it. Reminders fire even
 * for muted threads — an explicit user request beats a thread-level mute.
 */
@HiltWorker
class MessageReminderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val messageRepository: MessageRepository,
    private val threadRepository: ThreadRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1L)
        if (messageId == -1L) return Result.success()

        val message = messageRepository.getById(messageId) ?: return Result.success()
        // Unflag race: the reminder was cleared after this job was scheduled — nothing to post.
        if (message.remindAt == null) return Result.success()

        val thread = threadRepository.getById(message.threadId)
        val title = thread?.displayName?.takeIf { it.isNotBlank() }
            ?: formatPhoneNumber(message.address)

        val openIntent = PendingIntent.getActivity(
            context,
            messageId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_THREAD_ID, message.threadId)
                putExtra(MainActivity.EXTRA_SCROLL_TO_MESSAGE_ID, messageId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, PostmarkApplication.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Reply to: ${message.previewText}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Reply to: ${message.previewText}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(messageId.hashCode(), notification)

        return Result.success()
    }

    companion object {
        const val KEY_MESSAGE_ID = "messageId"

        private fun workName(messageId: Long) = "message_reminder_$messageId"

        /** Schedules (or reschedules, REPLACE) the reminder for [messageId] to fire at
         *  [remindAtMs]. A non-positive delay (time already elapsed) fires as soon as
         *  WorkManager runs it. */
        fun schedule(context: Context, messageId: Long, remindAtMs: Long) {
            val delayMs = (remindAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<MessageReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(messageId),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /** Cancels a pending reminder for [messageId] (called on unflag). */
        fun cancel(context: Context, messageId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(messageId))
        }
    }
}
