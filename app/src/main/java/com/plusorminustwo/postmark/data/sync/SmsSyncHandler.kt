package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.plusorminustwo.postmark.BuildConfig
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_NONE
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.previewText
import com.plusorminustwo.postmark.search.parser.AppleReactionParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsSyncHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val reactionParser: AppleReactionParser,
    private val statsUpdater: StatsUpdater
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Debug-only logs — filter logcat by tag "SmsSyncHandler" to follow MMS sync.
    private fun debugLog(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, msg) }

    companion object { private const val TAG = "SmsSyncHandler" }

    fun onSmsContentChanged(uri: Uri) {
        scope.launch { syncLatestSms(uri) }
    }

    fun onMmsContentChanged(uri: Uri) {
        scope.launch { syncLatestMms(uri) }
    }
    /**
     * Performs one incremental SMS + MMS pass on the caller's coroutine.
     * Called by [FirstLaunchSyncWorker] after the initial full sync completes to capture
     * any messages that arrived in the race window before the first DB commit.
     */
    suspend fun triggerCatchUp() {
        syncLatestSms(Telephony.Sms.CONTENT_URI)
        syncLatestMms(Uri.parse("content://mms"))
    }
    // ── Incremental sync ─────────────────────────────────────────────────────
    // Fetches every SMS row whose _id is greater than the highest id already
    // stored in Room.  This correctly handles burst delivery (multiple messages
    // arriving in a single ContentObserver notification) and is idempotent
    // (re-running when there are no new messages returns 0 rows instantly).
    //
    // When the local DB is empty (initial sync not yet complete) we bail out
    // immediately — FirstLaunchSyncWorker owns the full historical load.
    private suspend fun syncLatestSms(@Suppress("UNUSED_PARAMETER") uri: Uri) {
        // Nothing in DB yet → full sync not done; do not interfere with worker.
        val maxKnownId = messageRepository.getMaxId() ?: 0L
        if (maxKnownId <= 0L) return

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )

        // Fetch every SMS row we haven't seen yet, oldest-first so thread
        // metadata (lastMessageAt/Preview) ends up reflecting the true latest.
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms._ID} > ?",
            arrayOf(maxKnownId.toString()),
            "${Telephony.Sms._ID} ASC"
        ) ?: return

        val newMessages = mutableListOf<Message>()
        // Track threads ensured in this batch to avoid redundant DB lookups.
        val ensuredThreadIds = mutableSetOf<Long>()

        cursor.use {
            val idIdx      = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdx  = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx    = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx    = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx    = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (it.moveToNext()) {
                val id       = it.getLong(idIdx)
                val threadId = it.getLong(threadIdx)
                // Null address is valid for WAP push, carrier service, and some OEM ROMs.
                // Preserve the row with an empty fallback — never skip based on missing address.
                val address  = it.getString(addressIdx) ?: ""
                val body     = it.getString(bodyIdx) ?: ""
                val date     = it.getLong(dateIdx)
                val type     = it.getInt(typeIdx)
                // Drafts (3), outbox (4), and failed sends (5) are outgoing; only inbox (1) is received.
                val isSent   = type != Telephony.Sms.MESSAGE_TYPE_INBOX

                if (threadId !in ensuredThreadIds) {
                    ensureThread(threadId, address, date)
                    ensuredThreadIds += threadId
                }
                newMessages += Message(
                    id = id,
                    threadId = threadId,
                    address = address,
                    body = body,
                    timestamp = date,
                    isSent = isSent,
                    type = type,
                    // Sent messages arriving via incremental sync are waiting for the
                    // SmsSentDeliveryReceiver callback; start them as PENDING so the UI
                    // shows the clock icon.  Received messages have no delivery tracking.
                    deliveryStatus = if (isSent) DELIVERY_STATUS_PENDING else DELIVERY_STATUS_NONE
                )
            }
        }

        if (newMessages.isEmpty()) return

        // Persist all new messages in one batch.
        messageRepository.insertAll(newMessages)

        // Per-thread post-processing.
        newMessages.groupBy { it.threadId }.forEach { (threadId, msgs) ->
            // The list is ordered ASC so last() is the most recent.
            val latest = msgs.last()
            // Clean up any optimistic (negative-id) sent messages for this thread.
            messageRepository.deleteOptimisticMessages(threadId)
            threadRepository.updateLastMessageAt(threadId, latest.timestamp)
            threadRepository.updateLastMessagePreview(threadId, latest.previewText)
        }

        // One stats recompute covers all affected threads.
        statsUpdater.recomputeAll()

        // Apple reaction parser pass over new messages.
        newMessages.forEach { message ->
            val parsed = reactionParser.parse(message.body) ?: return@forEach
            if (!parsed.isRemoval) {
                val threadMessages = messageRepository.getByThread(message.threadId)
                val reaction = reactionParser.processIncomingMessage(message, threadMessages, message.address)
                if (reaction != null) messageRepository.insertReaction(reaction)
            }
        }
    }

    // ── Incremental MMS sync ──────────────────────────────────────────────────
    // Mirrors syncLatestSms() but for content://mms. Bound is derived from the
    // highest stored MMS id (which is offset by MMS_ID_OFFSET) minus the offset,
    // giving the raw MMS _id to use in the WHERE clause.
    private suspend fun syncLatestMms(@Suppress("UNUSED_PARAMETER") uri: Uri) {
        val maxStoredId = messageRepository.getMaxMmsId() ?: 0L
        // Bail only when the initial full sync hasn't run at all (both tables empty).
        // If SMS is populated but MMS count is 0 — which happens when Samsung's
        // content://mms returned empty during the first sync — we must proceed so
        // that incoming MMS can still be picked up incrementally.
        if (maxStoredId <= 0L && messageRepository.getMaxId() == null) {
            debugLog("syncLatestMms: initial sync not yet complete — skipping incremental pass")
            return
        }
        // coerceAtLeast(0) prevents a negative bound when no MMS has been stored yet;
        // "_id > 0" correctly returns all rows because MMS IDs start at 1.
        val maxRawId = (maxStoredId - MMS_ID_OFFSET).coerceAtLeast(0L)
        debugLog("syncLatestMms: maxStoredId=$maxStoredId  maxRawId=$maxRawId")

        val cursor = context.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "thread_id", "date", "msg_box"),
            "_id > ?", arrayOf(maxRawId.toString()),
            "_id ASC"
        ) ?: run {
            Log.w(TAG, "syncLatestMms: cursor was null — provider unavailable or permission denied")
            return
        }

        debugLog("syncLatestMms: cursor has ${cursor.count} new MMS rows")

        val newMessages       = mutableListOf<Message>()
        val ensuredThreadIds  = mutableSetOf<Long>()

        cursor.use {
            val idIdx     = it.getColumnIndexOrThrow("_id")
            val threadIdx = it.getColumnIndexOrThrow("thread_id")
            val dateIdx   = it.getColumnIndexOrThrow("date")
            val boxIdx    = it.getColumnIndexOrThrow("msg_box")

            while (it.moveToNext()) {
                val rawId     = it.getLong(idIdx)
                val threadId  = it.getLong(threadIdx)
                val dateSec   = it.getLong(dateIdx)
                val msgBox    = it.getInt(boxIdx)
                val id        = MMS_ID_OFFSET + rawId
                // Drafts (3) and outbox (4) are outgoing; only inbox (1) is received.
                val isSent    = msgBox != android.provider.Telephony.Mms.MESSAGE_BOX_INBOX
                val timestamp = dateSec * 1000L
                val parts     = getMmsBodyIncremental(rawId)
                val address   = getMmsAddressIncremental(rawId, isSent)
                debugLog("syncLatestMms: rawId=$rawId  mimeType=${parts.mimeType}  attachmentUri=${parts.attachmentUri}")

                if (threadId !in ensuredThreadIds) {
                    ensureThread(threadId, address, timestamp)
                    ensuredThreadIds += threadId
                }

                newMessages += Message(
                    id = id,
                    threadId = threadId,
                    address = address,
                    body = parts.body,
                    timestamp = timestamp,
                    isSent = isSent,
                    type = msgBox,
                    isMms = true,
                    attachmentUri = parts.attachmentUri,
                    mimeType = parts.mimeType
                )
            }
        }

        if (newMessages.isEmpty()) return

        messageRepository.insertAll(newMessages)

        newMessages.groupBy { it.threadId }.forEach { (threadId, msgs) ->
            val latest = msgs.last()
            messageRepository.deleteOptimisticMessages(threadId)
            threadRepository.updateLastMessageAt(threadId, latest.timestamp)
            // Use emoji label for media-only MMS in the thread preview.
            val preview = latest.previewText
            threadRepository.updateLastMessagePreview(threadId, preview)
        }

        statsUpdater.recomputeAll()
    }

    // Reads text body and first media attachment from the given MMS part table.
    // Returns MmsParts with a stable content://mms/part/{id} URI for image/video/audio.
    private fun getMmsBodyIncremental(mmsId: Long): MmsParts {
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf("_id", "ct", "text"), null, null, null
        ) ?: run {
            Log.w(TAG, "getMmsBodyIncremental: parts cursor null for mmsId=$mmsId")
            return MmsParts("[MMS]", null, null)
        }
        debugLog("getMmsBodyIncremental: mmsId=$mmsId  partCount=${cursor.count}")
        val sb = StringBuilder()
        var attachmentUri: String? = null
        var mimeType: String? = null
        cursor.use {
            val idIdx   = it.getColumnIndexOrThrow("_id")
            val ctIdx   = it.getColumnIndexOrThrow("ct")
            val textIdx = it.getColumnIndexOrThrow("text")
            while (it.moveToNext()) {
                val ct     = it.getString(ctIdx) ?: continue
                val partId = it.getLong(idIdx)
                when {
                    ct == "text/plain" ->
                        sb.append(it.getString(textIdx) ?: "")
                    ct == "application/smil" -> Unit
                    ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") -> {
                        debugLog("getMmsBodyIncremental: found media part ct=$ct  partId=$partId")
                        if (attachmentUri == null) {
                            attachmentUri = "content://mms/part/$partId"
                            mimeType = ct
                        }
                    }
                    else -> debugLog("getMmsBodyIncremental: skipping unknown part ct=$ct")
                }
            }
        }
        return MmsParts(sb.toString().trim(), attachmentUri, mimeType)
    }

    private fun getMmsAddressIncremental(mmsId: Long, isSent: Boolean): String {
        val addrType = if (isSent) 151 else 137
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            "type = ?", arrayOf(addrType.toString()), null
        ) ?: return "Unknown"
        return cursor.use {
            if (it.moveToFirst()) {
                val addr = it.getString(it.getColumnIndexOrThrow("address")) ?: "Unknown"
                // "insert-address-token" is a Samsung PDU placeholder; treat as unknown.
                if (addr == "insert-address-token") "Unknown" else addr
            } else "Unknown"
        }
    }

    private suspend fun ensureThread(threadId: Long, address: String, timestamp: Long) {
        if (threadRepository.getById(threadId) != null) return
        val displayName = lookupContactName(address) ?: address.ifEmpty { "Unknown" }
        threadRepository.upsert(
            com.plusorminustwo.postmark.domain.model.Thread(
                id = threadId,
                displayName = displayName,
                address = address,
                lastMessageAt = timestamp,
                backupPolicy = BackupPolicy.GLOBAL
            )
        )
    }

    private fun lookupContactName(address: String): String? {
        // An empty address would produce content://com.android.contacts/phone_lookup/ with no
        // segment, which may match every contact on some ROMs. Skip the lookup entirely.
        if (address.isEmpty()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                else null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    // Carries the text body and optional media attachment info for one MMS PDU.
    private data class MmsParts(
        val body: String,
        val attachmentUri: String?,
        val mimeType: String?
    )
}
