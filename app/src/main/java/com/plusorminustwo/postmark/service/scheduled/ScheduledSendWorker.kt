package com.plusorminustwo.postmark.service.scheduled

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
import com.plusorminustwo.postmark.data.repository.ScheduledMessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.scheduled.ScheduledSendOutcome
import com.plusorminustwo.postmark.domain.scheduled.scheduledSendDecision
import com.plusorminustwo.postmark.service.sms.SmsSendDispatcher
import com.plusorminustwo.postmark.ui.MainActivity
import com.plusorminustwo.postmark.util.isDefaultSmsApp
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Sends a scheduled ("Schedule send") text when its chosen time arrives.
 *
 * Scheduling mirrors [com.plusorminustwo.postmark.service.reminder.MessageReminderWorker]: a plain
 * [OneTimeWorkRequest] with [setInitialDelay] equal to the wait until the send time — deliberately
 * NOT an exact alarm (Postmark keeps zero exact-alarm surface). The ± few minutes of WorkManager
 * inexactness is an OPEN OWNER QUESTION (the owner may later want SCHEDULE_EXACT_ALARM for
 * punctuality); the seam is kept to a single [schedule] call site so that swap is localized.
 *
 * Unique work name `scheduled_send_<id>` with [ExistingWorkPolicy.REPLACE] so editing a schedule
 * reschedules cleanly and [cancel] removes it. WorkManager persists its queue across reboots for
 * free.
 *
 * On fire (decision table in [scheduledSendDecision]):
 *  - row missing → success (the user cancelled / sent-now between scheduling and firing).
 *  - not the default SMS app → KEEP the row, post a notification deep-linking to the thread, and
 *    return success (no retry loop — the user must re-grant the role and act manually).
 *  - otherwise → delete the row and hand the text to the ONE shared [SmsSendDispatcher] send path,
 *    so an offline-at-fire-time send parks as QUEUED exactly like a live send.
 */
@HiltWorker
class ScheduledSendWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val scheduledMessageRepository: ScheduledMessageRepository,
    private val threadRepository: ThreadRepository,
    private val smsSendDispatcher: SmsSendDispatcher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val scheduledId = inputData.getLong(KEY_SCHEDULED_ID, -1L)
        if (scheduledId == -1L) return Result.success()

        val row = scheduledMessageRepository.getById(scheduledId)
        return when (scheduledSendDecision(rowExists = row != null, isDefaultSms = context.isDefaultSmsApp())) {
            ScheduledSendOutcome.ROW_MISSING -> Result.success()
            ScheduledSendOutcome.NOT_DEFAULT_SMS -> {
                postCouldNotSendNotification(row!!.threadId, row.address)
                Result.success()
            }
            ScheduledSendOutcome.SEND -> {
                // Delete BEFORE dispatch so the transient row can't briefly coexist with the
                // real optimistic message the send inserts. The observing thread's scheduled
                // bubble vanishes and the real bubble appears via the normal insert flow.
                scheduledMessageRepository.deleteById(scheduledId)
                val now = System.currentTimeMillis()
                smsSendDispatcher.dispatchSmsSend(
                    threadId = row!!.threadId,
                    address  = row.address,
                    body     = row.body,
                    tempId   = -now,
                    now      = now,
                )
                Result.success()
            }
        }
    }

    /** Posts the "couldn't send" notification (reminders channel) deep-linked to [threadId]. */
    private suspend fun postCouldNotSendNotification(threadId: Long, address: String) {
        val title = threadRepository.getById(threadId)?.displayName?.takeIf { it.isNotBlank() }
            ?: formatPhoneNumber(address)

        val openIntent = PendingIntent.getActivity(
            context,
            threadId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_THREAD_ID, threadId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = "Couldn't send scheduled message — tap to review"
        val notification = NotificationCompat.Builder(context, PostmarkApplication.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_TAG_BASE + threadId.hashCode(), notification)
    }

    companion object {
        const val KEY_SCHEDULED_ID = "scheduledId"
        private const val NOTIFICATION_TAG_BASE = 0x5C4E_0000

        private fun workName(scheduledId: Long) = "scheduled_send_$scheduledId"

        /** Schedules (or reschedules, REPLACE) the send for [scheduledId] to fire at [scheduledAtMs].
         *  A non-positive delay fires as soon as WorkManager runs it. Single seam for a possible
         *  future exact-alarm swap (see class KDoc). */
        fun schedule(context: Context, scheduledId: Long, scheduledAtMs: Long) {
            val delayMs = (scheduledAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<ScheduledSendWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_SCHEDULED_ID to scheduledId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(scheduledId),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /** Cancels a pending scheduled send (called on Cancel / Edit / Send-now). */
        fun cancel(context: Context, scheduledId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(scheduledId))
        }
    }
}
