package com.plusorminustwo.postmark.service.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.plusorminustwo.postmark.data.contacts.lookupContactName
import com.plusorminustwo.postmark.data.preferences.PrivacyModeRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import com.plusorminustwo.postmark.data.sync.SyncLogger
import com.plusorminustwo.postmark.domain.logging.redactPhone
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [BroadcastReceiver] that handles incoming SMS messages.
 *
 * Receives both [Telephony.Sms.Intents.SMS_DELIVER_ACTION] (when Postmark is the
 * default SMS app — the OS will NOT persist the row, so we must write it) and
 * [Telephony.Sms.Intents.SMS_RECEIVED_ACTION] (when another app is default — the row
 * is already written, so we only trigger a Room sync).
 *
 * Uses [goAsync] to extend the receiver lifetime for IO work without ANR risk.
 * After persisting the row and syncing Room, posts an incoming-message notification
 * unless the thread is muted or notifications are disabled for that address.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject lateinit var syncHandler: SmsSyncHandler
    @Inject lateinit var threadRepository: ThreadRepository
    @Inject lateinit var privacyModeRepository: PrivacyModeRepository
    @Inject lateinit var syncLogger: SyncLogger
    @Inject lateinit var incomingNotifier: IncomingNotifier

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    ?.takeIf { it.isNotEmpty() } ?: return

                // Parse PDU fields on the main thread — no IO, always fast.
                val isDeliver   = intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION
                val rawSender   = messages[0].originatingAddress ?: ""
                // Human-readable fallback used for notification display only.
                val sender      = rawSender.ifEmpty { "Unknown" }
                // Reconstruct full body from all PDU parts (multi-part SMS arrives as array).
                val body        = messages.joinToString("") { it.messageBody ?: "" }
                val timestampMs = messages[0].timestampMillis

                /* Log broadcast receipt synchronously before goAsync() — if the process
                 * is killed mid-async we still have a "broadcast arrived" entry in the log. */
                syncLogger.log("SmsReceiver", if (isDeliver) "DELIVER_ACTION from=${rawSender.redactPhone()}" else "RECEIVED_ACTION from=${rawSender.redactPhone()}")

                /* goAsync() extends the BroadcastReceiver lifetime so the OS does not
                 * reclaim the process before our IO and notification work is done. */
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        /* ── Persist to system SMS store (default SMS app only) ─────────
                         * SMS_DELIVER_ACTION fires exclusively for the default SMS app.
                         * We are solely responsible for writing the row to
                         * content://sms/inbox — the OS will NOT do it for us.
                         * All ContentResolver calls are on Dispatchers.IO to avoid
                         * blocking the main thread (potential ANR). */
                        if (isDeliver) {
                            persistToSystemStore(context, rawSender, body, timestampMs)
                        }
                        /* SMS_RECEIVED_ACTION: another app is default, it already wrote the row.
                         * Do not insert again — that would create a duplicate. */

                        /* Trigger Room incremental sync. The content observer will also fire
                         * once the row lands, but this explicit call guarantees Room is updated
                         * even if the observer notification is delayed or lost. */
                        syncHandler.onSmsContentChanged(Telephony.Sms.CONTENT_URI)

                        // Check mute/notifications before posting the notification banner.
                        val notificationsEnabled =
                            threadRepository.isNotificationsEnabledByAddress(rawSender)
                        if (notificationsEnabled && !threadRepository.isMutedByAddress(rawSender)) {
                            /* Look up the display name from ContactsContract first — it is
                             * always current even for contacts added after the initial sync
                             * (which can leave a stale phone number in Room's displayName).
                             * Falls back to Room's stored name, then to the raw number. */
                            val displayName = context.lookupContactName(rawSender)
                                ?: threadRepository.getDisplayNameByAddress(rawSender)
                                ?: sender
                            syncLogger.log("SmsReceiver", "notification: address=${rawSender.redactPhone()} nameResolved=${displayName != sender}")
                            /* Resolve the canonical thread id once, reused for the deep-link
                             * and the group check below. */
                            val threadId: Long = try {
                                Telephony.Threads.getOrCreateThreadId(context, rawSender)
                            } catch (e: Exception) {
                                Log.w(TAG, "getOrCreateThreadId failed for notification", e)
                                -1L
                            }
                            /* Suppress the inline reply action on group threads: a single-
                             * address SMS reply can't reach the whole group. Keyed on the
                             * resolved thread's roster, not the address — a group thread's
                             * address column holds one member's number, which also keys that
                             * member's own 1:1 thread, so an address check would only ever
                             * false-positive (GROUP_MESSAGING_SPEC §2.2). Always false for
                             * incoming SMS today (group traffic is MMS); correct future-
                             * proofing for when MMS notifications land (P3). */
                            val isGroupThread =
                                threadId > 0L && (threadRepository.getById(threadId)?.participants?.size ?: 0) > 1
                            incomingNotifier.notify(
                                notifKey = rawSender.hashCode(),
                                threadId = threadId,
                                address = rawSender,
                                title = displayName,
                                body = body,
                                privacyMode = privacyModeRepository.isEnabled(),
                                allowDirectReply = !isGroupThread
                            )
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    // ── Persist to content://sms/inbox ────────────────────────────────────────
    /**
     * Writes one inbox row to the system SMS store for a newly received message.
     * Sets THREAD_ID explicitly via [Telephony.Threads.getOrCreateThreadId] so that
     * OEM ROMs that don't auto-assign it can't create duplicate threads for the same
     * contact. PROTOCOL = 0 distinguishes SMS from WAP push (= 1).
     */
    private fun persistToSystemStore(
        context: Context,
        rawSender: String,
        body: String,
        timestampMs: Long
    ) {
        /* Resolve or create the canonical thread ID for this address.
         * Wrapped in try/catch because some OEMs throw on malformed addresses. */
        val threadId: Long = try {
            if (rawSender.isNotEmpty())
                Telephony.Threads.getOrCreateThreadId(context, rawSender)
            else 0L
        } catch (e: Exception) {
            Log.w(TAG, "getOrCreateThreadId failed for sender=${rawSender.redactPhone()}", e)
            0L
        }

        val cv = ContentValues().apply {
            put(Telephony.Sms.Inbox.ADDRESS,   rawSender)
            put(Telephony.Sms.Inbox.BODY,      body)
            put(Telephony.Sms.Inbox.DATE,      System.currentTimeMillis())
            put(Telephony.Sms.Inbox.DATE_SENT, timestampMs)
            put(Telephony.Sms.Inbox.READ,      0)
            put(Telephony.Sms.Inbox.SEEN,      0)
            put(Telephony.Sms.PROTOCOL,        0)  // 0 = SMS, 1 = WAP push
            if (threadId > 0L) put(Telephony.Sms.THREAD_ID, threadId)
        }

        try {
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, cv)
            if (uri != null) {
                syncLogger.log("SmsReceiver", "wrote inbox row: from=${rawSender.redactPhone()} threadId=$threadId uri=$uri")
            } else {
                syncLogger.logError("SmsReceiver", "Insert returned null for sender=${rawSender.redactPhone()} — message may be lost")
            }
        } catch (e: Exception) {
            syncLogger.logError("SmsReceiver", "Write to content://sms/inbox FAILED for sender=${rawSender.redactPhone()}", e)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
