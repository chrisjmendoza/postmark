package com.plusorminustwo.postmark.service.sms

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.repository.MessageRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Flushes the offline send queue: every SMS that failed to send because the phone had no
 * service (status [com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_QUEUED]) is
 * re-sent, in timestamp order, once the network is back.
 *
 * The queue is not a separate structure — it's just the Room rows carrying the QUEUED
 * status (parked there by [SmsSentDeliveryReceiver] when a send hit a queue-worthy failure,
 * or by [com.plusorminustwo.postmark.ui.thread.ThreadViewModel] when a new send joins a
 * thread that already has queued messages). This worker reads them all, oldest first, and
 * hands each back to [SmsManagerWrapper].
 *
 * Scheduling ([enqueue]): unique work "send-queue-flush" with [ExistingWorkPolicy.KEEP] so
 * overlapping triggers (receiver, new send, app start) collapse to one run; a
 * [NetworkType.CONNECTED] constraint and exponential backoff cover the wait for service.
 * NetworkType.CONNECTED is a heuristic for "radio likely back" — SMS actually needs cell
 * service, not data connectivity, so the two can diverge (Wi-Fi up, cell still down); that
 * is accepted, and the exponential backoff on any re-failure covers the gap.
 */
@HiltWorker
class SendQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val messageRepository: MessageRepository,
    private val smsManagerWrapper: SmsManagerWrapper
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // All queued rows, oldest first, so sends leave in the order they were composed.
        val queued = messageRepository.getQueuedMessages()
        if (queued.isEmpty()) return Result.success()

        var processed = 0
        for (message in queued) {
            // MMS is never queued by this feature (only queue-worthy SMS radio failures park
            // rows here); ignore any MMS row defensively rather than mis-sending it as text.
            if (message.isMms) continue

            /* Move to PENDING before dispatch: the send is now in flight, and if it fails
             * again with a queue-worthy code SmsSentDeliveryReceiver re-parks it as QUEUED
             * and re-enqueues a fresh flush. */
            messageRepository.updateDeliveryStatus(message.id, DELIVERY_STATUS_PENDING)
            smsManagerWrapper.sendTextMessage(message.address, message.body, message.id)
            processed++
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "send-queue-flush"

        /** Enqueues a flush of the offline send queue. Idempotent via unique-work KEEP —
         *  the receiver, a new queued send, and app start can all call this freely. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SendQueueWorker>()
                .setConstraints(
                    Constraints.Builder()
                        // Heuristic for "radio likely back" — see class doc.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
