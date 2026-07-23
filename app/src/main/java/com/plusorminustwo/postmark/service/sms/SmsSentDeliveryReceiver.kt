package com.plusorminustwo.postmark.service.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_QUEUED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.data.reaction.ReactionFallbackParser
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import com.plusorminustwo.postmark.data.sync.SyncLogger
import com.plusorminustwo.postmark.domain.logging.redactPhone
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.reaction.shouldRequeueOrphanedReactionFallback
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [BroadcastReceiver] that handles SMS sent and delivery confirmations.
 *
 * Receives the [PendingIntent]s registered by [SmsManagerWrapper.sendTextMessage]:
 *  - **Sent intent** (`ACTION_SMS_SENT`): updates the Room delivery status to
 *    [DELIVERY_STATUS_SENT] or [DELIVERY_STATUS_FAILED] and mirrors the result to
 *    the system telephony content provider.
 *  - **Delivery intent** (`ACTION_SMS_DELIVERED`): updates Room to
 *    [DELIVERY_STATUS_DELIVERED] when the SMSC acknowledges the handset receipt.
 *
 * Uses [smsRowId] (the positive content-provider row ID captured at send time) as
 * the canonical Room key, falling back to the optimistic `messageId` only if the
 * extra is absent.
 */
@AndroidEntryPoint
class SmsSentDeliveryReceiver : BroadcastReceiver() {

    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var syncLogger: SyncLogger
    @Inject lateinit var smsSyncHandler: SmsSyncHandler
    @Inject lateinit var multipartSendTracker: MultipartSendTracker
    @Inject lateinit var reactionParser: ReactionFallbackParser

    override fun onReceive(context: Context, intent: Intent) {
        val messageId  = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        /* smsRowId is the positive content-provider _id captured at insert time in
         * SmsManagerWrapper. By the time this fires, SmsSyncHandler has already replaced
         * the optimistic negative-ID row with a real Room row keyed by smsRowId.
         * Fall back to messageId only if the extra is absent (legacy PendingIntent). */
        val smsRowId   = intent.getLongExtra(EXTRA_SMS_ROW_ID, -1L)
        val roomId     = if (smsRowId > 0L) smsRowId else messageId
        if (roomId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SMS_SENT -> {
                        /*
                         * RESULT_CANCELED (0) is ambiguous — it fires when the PendingIntent
                         * is cancelled by the OS (e.g. process restart) rather than being a
                         * real send failure. Actual SmsManager error codes are all ≥ 1.
                         * Treating 0 as FAILED would cause a red-! flash even when the
                         * message goes through successfully.
                         */
                        val isSendOk = resultCode == Activity.RESULT_OK   // -1 = success
                        val isKnownFailure = resultCode > 0

                        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
                        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, -1)
                        val address = intent.getStringExtra(EXTRA_ADDRESS)

                        if (partIndex < 0 || partCount < 1) {
                            /* Legacy PendingIntent from before this app update carried no
                             * part extras — fall back to the old direct per-result behaviour
                             * so an in-flight send from the previous version still resolves. */
                            handleLegacySentResult(context, intent, roomId, smsRowId, isSendOk, isKnownFailure, address)
                        } else {
                            /* Aggregate every part through the tracker so a multipart send only
                             * goes SENT when ALL parts succeed, and a single failed part is
                             * terminal (a later part's Ok can't overwrite FAILED back to SENT). */
                            val outcome = when {
                                isSendOk       -> MultipartSendTracker.Outcome.Ok
                                isKnownFailure -> MultipartSendTracker.Outcome.KnownFailure
                                else           -> MultipartSendTracker.Outcome.Ambiguous
                            }
                            /* The recovery payload rides only the last part's intent; the tracker
                             * stashes it until the (possibly out-of-order) completing part arrives. */
                            val recovery = if (!address.isNullOrEmpty()) {
                                MultipartSendTracker.RecoveryPayload(address, intent.getStringExtra(EXTRA_BODY) ?: "")
                            } else null

                            val decision = multipartSendTracker.record(roomId, partIndex, partCount, outcome, recovery)
                            syncLogger.log(
                                "SmsSentDelivery",
                                "SMS_SENT roomId=$roomId smsRowId=$smsRowId part=${partIndex + 1}/$partCount " +
                                    "outcome=$outcome decision=${decision::class.simpleName}"
                            )

                            when (decision) {
                                is MultipartSendTracker.Decision.MarkSent -> {
                                    messageRepository.updateDeliveryStatus(roomId, DELIVERY_STATUS_SENT)
                                    /* Recovery: the radio confirmed the send but the pre-send
                                     * insert into content://sms/sent never produced a row
                                     * (smsRowId == -1). Use the stashed payload — it may have
                                     * ridden a different part than the one that completed here. */
                                    val recoveryAddress = decision.recovery?.address
                                    if (shouldRecoverSentRow(true, smsRowId, recoveryAddress)) {
                                        recoverMissingSentRow(context, recoveryAddress!!, decision.recovery.body)
                                    }
                                }
                                is MultipartSendTracker.Decision.MarkFailed -> {
                                    /* A no-service / radio-off failure isn't a real failure — the
                                     * message can go out unchanged when service returns. Park it in
                                     * the send queue instead of marking it FAILED. address/body ride
                                     * the last part (present for single-part reaction fallbacks) and
                                     * seed the orphaned-fallback requeue below. */
                                    applyFailedOutcome(
                                        context, roomId, smsRowId, isQueueWorthyFailure(resultCode),
                                        address, intent.getStringExtra(EXTRA_BODY)
                                    )
                                }
                                is MultipartSendTracker.Decision.None -> {
                                    /* Still awaiting parts, an ambiguous part, or already decided —
                                     * leave the current status (PENDING) untouched. */
                                }
                            }
                        }
                    }
                    ACTION_SMS_DELIVERED -> {
                        syncLogger.log("SmsSentDelivery", "SMS_DELIVERED roomId=$roomId smsRowId=$smsRowId")
                        messageRepository.updateDeliveryStatus(roomId, DELIVERY_STATUS_DELIVERED)

                        // Mark delivery confirmed in the content provider.
                        if (smsRowId > 0L) {
                            val cv = ContentValues().apply {
                                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
                            }
                            context.contentResolver.update(
                                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, smsRowId),
                                cv, null, null
                            )
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Pre-update-legacy path: a PendingIntent registered by an older app version that
     * lacks the per-part extras. Applies the result directly (the pre-aggregation
     * behaviour) so an in-flight send from before the update still resolves.
     */
    private suspend fun handleLegacySentResult(
        context: Context,
        intent: Intent,
        roomId: Long,
        smsRowId: Long,
        isSendOk: Boolean,
        isKnownFailure: Boolean,
        address: String?,
    ) {
        val label = when {
            isSendOk       -> "SENT"
            isKnownFailure -> "FAILED (resultCode=$resultCode)"
            else           -> "AMBIGUOUS (resultCode=$resultCode) — leaving as PENDING"
        }
        syncLogger.log("SmsSentDelivery", "SMS_SENT (legacy, no part extras) roomId=$roomId smsRowId=$smsRowId result=$label")
        when {
            isSendOk -> {
                messageRepository.updateDeliveryStatus(roomId, DELIVERY_STATUS_SENT)
                if (shouldRecoverSentRow(true, smsRowId, address)) {
                    recoverMissingSentRow(context, address!!, intent.getStringExtra(EXTRA_BODY) ?: "")
                }
            }
            isKnownFailure -> {
                applyFailedOutcome(
                    context, roomId, smsRowId, isQueueWorthyFailure(resultCode),
                    address, intent.getStringExtra(EXTRA_BODY)
                )
            }
            // resultCode == 0: ambiguous — leave as PENDING.
        }
    }

    /**
     * Applies a send failure. A [queueWorthy] failure (no service / radio off) parks the row
     * in the offline send queue ([DELIVERY_STATUS_QUEUED]) and enqueues a flush — the provider
     * status is deliberately left PENDING (not mirrored to STATUS_FAILED) so nothing presents
     * the message as permanently failed while it waits for service. Any other failure is a real
     * failure: FAILED in Room, mirrored to the provider so other apps stop showing it as pending.
     */
    private suspend fun applyFailedOutcome(
        context: Context,
        roomId: Long,
        smsRowId: Long,
        queueWorthy: Boolean,
        recoveryAddress: String? = null,
        recoveryBody: String? = null,
    ) {
        if (queueWorthy) {
            messageRepository.updateDeliveryStatus(roomId, DELIVERY_STATUS_QUEUED)
            /* Orphaned reaction-fallback guard: a sent reaction fallback is resolved out of
             * Room by incremental sync (never a bubble), so the update above finds no row at
             * roomId and the QUEUED park is a silent no-op — the transmission would be dropped
             * when service returns. When the failed body is a fallback and no row exists, park
             * a fresh QUEUED optimistic row so SendQueueWorker resends it (it then syncs and
             * resolves normally, deduped against the local pill). */
            if (shouldRequeueOrphanedReactionFallback(
                    roomRowExists = messageRepository.getById(roomId) != null,
                    isReactionFallback = recoveryBody?.let { reactionParser.isReactionFallback(it) } == true,
                    address = recoveryAddress,
                    body = recoveryBody,
                )
            ) {
                requeueOrphanedReactionFallback(context, recoveryAddress!!, recoveryBody!!)
            }
            SendQueueWorker.enqueue(context)
        } else {
            messageRepository.updateDeliveryStatus(roomId, DELIVERY_STATUS_FAILED)
            /* Mirror failure status into content://sms so third-party apps
             * (e.g. Google Messages) don't keep showing it as pending. */
            mirrorProviderStatus(context, smsRowId, Telephony.Sms.STATUS_FAILED)
        }
    }

    /**
     * Parks a fresh QUEUED optimistic row for a reaction fallback whose real send failed
     * for want of service but whose Room row was already resolved away by sync. [SendQueueWorker]
     * flushes it (negative id → fresh provider insert) once service returns; the resulting
     * provider row syncs and resolves as a reaction like any other, deduped against the local
     * pill by reactionExists. Never deletes anything — a duplicate provider row (both resolve
     * to one Reaction) is the accepted cost of not dropping the send.
     */
    private suspend fun requeueOrphanedReactionFallback(context: Context, address: String, body: String) {
        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            syncLogger.logError("SmsSentDelivery", "requeue: getOrCreateThreadId failed for ${address.redactPhone()} — fallback not requeued", e)
            return
        }
        val now = System.currentTimeMillis()
        messageRepository.insert(
            Message(
                id = -now,
                threadId = threadId,
                address = address,
                body = body,
                timestamp = now,
                isSent = true,
                type = Telephony.Sms.MESSAGE_TYPE_SENT,
                deliveryStatus = DELIVERY_STATUS_QUEUED,
            )
        )
        syncLogger.log("SmsSentDelivery", "requeued orphaned reaction fallback for ${address.redactPhone()} (no Room row at roomId)")
    }

    /** Mirrors a send outcome into `content://sms` so other apps see it (no-op if smsRowId absent). */
    private fun mirrorProviderStatus(context: Context, smsRowId: Long, status: Int) {
        if (smsRowId <= 0L) return
        val cv = ContentValues().apply { put(Telephony.Sms.STATUS, status) }
        context.contentResolver.update(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, smsRowId),
            cv, null, null
        )
    }

    /**
     * Re-creates the `content://sms/sent` row for a radio-confirmed send whose
     * pre-send insert failed. Mirrors the ContentValues written by
     * [SmsManagerWrapper.sendTextMessage], except STATUS is [Telephony.Sms.STATUS_NONE]:
     * the delivery PendingIntent carries `smsRowId = -1` and can never reach this
     * recovered row, so no delivery report is pending on it.
     *
     * After inserting, awaits [SmsSyncHandler.triggerCatchUp] so the real Room row
     * exists (sync creates sent rows as PENDING awaiting this receiver — which has
     * already fired), then marks it SENT directly.
     */
    private suspend fun recoverMissingSentRow(context: Context, address: String, body: String) {
        val newRowId = try {
            val now = System.currentTimeMillis()
            // Same Samsung/MIUI mis-grouping guard as SmsManagerWrapper and SmsReceiver.
            val threadId = try {
                Telephony.Threads.getOrCreateThreadId(context, address)
            } catch (e: Exception) { 0L }
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.DATE_SENT, now)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
                if (threadId > 0L) put(Telephony.Sms.THREAD_ID, threadId)
            }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
                ?.let { ContentUris.parseId(it) } ?: -1L
        } catch (e: Exception) {
            syncLogger.logError("SmsSentDelivery", "recovery insert FAILED for address=${address.redactPhone()} — sent row remains missing", e)
            return
        }
        if (newRowId <= 0L) {
            syncLogger.logError("SmsSentDelivery", "recovery insert returned null uri for address=${address.redactPhone()} — sent row remains missing")
            return
        }
        syncLogger.log("SmsSentDelivery", "recovered missing sent row _id=$newRowId for address=${address.redactPhone()}")
        smsSyncHandler.triggerCatchUp()
        messageRepository.updateDeliveryStatus(newRowId, DELIVERY_STATUS_SENT)
    }

    companion object {
        const val ACTION_SMS_SENT      = "com.plusorminustwo.postmark.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.plusorminustwo.postmark.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID     = "message_id"
        // Real content-provider _id written to content://sms/sent at send time.
        const val EXTRA_SMS_ROW_ID     = "sms_row_id"
        /* Per-part identity for MultipartSendTracker aggregation. Absent on legacy
         * (pre-update) PendingIntents, which fall back to direct per-result handling. */
        const val EXTRA_PART_INDEX     = "part_index"
        const val EXTRA_PART_COUNT     = "part_count"
        /* Recovery payload (final part only): lets this receiver re-create the sent
         * row when the pre-send insert failed but the radio send succeeded. */
        const val EXTRA_ADDRESS        = "address"
        const val EXTRA_BODY           = "body"
    }
}

/**
 * True when the sent row must be re-created: the radio send succeeded but the
 * pre-send insert never produced a content-provider row, and the PendingIntent
 * carries the recovery address. Pure — unit-tested in SentRowRepairTest.
 */
internal fun shouldRecoverSentRow(sendSucceeded: Boolean, smsRowId: Long, address: String?): Boolean =
    sendSucceeded && smsRowId <= 0L && !address.isNullOrEmpty()
