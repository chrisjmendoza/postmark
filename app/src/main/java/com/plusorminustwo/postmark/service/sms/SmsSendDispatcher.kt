package com.plusorminustwo.postmark.service.sms

import android.content.Context
import android.provider.Telephony
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_QUEUED
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.domain.model.Message
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The queue-aware 1:1 SMS dispatch, extracted from `ThreadViewModel.dispatchSmsSend` so BOTH the
 * ViewModel (user-composed sends, outbound reaction fallbacks) and
 * [com.plusorminustwo.postmark.service.scheduled.ScheduledSendWorker] (a scheduled send firing)
 * go through ONE send path — the worker must not reimplement send logic, and an offline-at-
 * fire-time scheduled send must park as [DELIVERY_STATUS_QUEUED] exactly like a live send.
 *
 * Inserts an optimistic negative-id row carrying [body], then either parks it QUEUED and kicks
 * [SendQueueWorker] (when the thread already has queued sends waiting for service, so this one
 * can't overtake them) or hands it straight to the radio as [DELIVERY_STATUS_PENDING]. The
 * scroll-to-bottom UX cue stays in the ViewModel — this abstraction is UI-agnostic.
 */
@Singleton
class SmsSendDispatcher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messageRepository: MessageRepository,
    private val smsManagerWrapper: SmsManagerWrapper,
) {
    /**
     * Dispatches [body] to [address] within [threadId]. [tempId] is the negative optimistic-row
     * id (conventionally `-now`); [now] is the row timestamp.
     */
    suspend fun dispatchSmsSend(
        threadId: Long,
        address: String,
        body: String,
        tempId: Long,
        now: Long,
    ) {
        val joinQueue = messageRepository.hasQueuedInThread(threadId)
        val optimistic = Message(
            id             = tempId,
            threadId       = threadId,
            address        = address,
            body           = body,
            timestamp      = now,
            isSent         = true,
            type           = Telephony.Sms.MESSAGE_TYPE_SENT,
            deliveryStatus = if (joinQueue) DELIVERY_STATUS_QUEUED else DELIVERY_STATUS_PENDING
        )
        messageRepository.insert(optimistic)
        if (joinQueue) {
            SendQueueWorker.enqueue(context)
        } else {
            smsManagerWrapper.sendTextMessage(address, body, tempId)
        }
    }
}
