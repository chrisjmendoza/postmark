package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.plusorminustwo.postmark.BuildConfig
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_NONE
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.previewText
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import com.plusorminustwo.postmark.search.parser.ReactionFallbackParser
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles incremental SMS and MMS sync whenever the system content provider changes.
 *
 * Architecture — conflated channels + mutexes:
 *  - Each transport (SMS and MMS) has its own [Channel.CONFLATED] work channel.
 *    If a sync is already running when a new content-observer notification arrives,
 *    at most one follow-up run is queued; additional notifications are dropped.
 *    This prevents O(N) coroutines piling up during burst MMS delivery.
 *  - A per-transport [Mutex] ensures only one sync path runs at a time, and that
 *    [triggerCatchUp] cannot race with the channel consumer on the same path.
 *
 * Call [onSmsContentChanged] / [onMmsContentChanged] from [SmsContentObserver].
 * Call [triggerCatchUp] after [SmsHistoryImportWorker] completes to pick up any
 * messages that arrived during the bulk import.
 */
@Singleton
class SmsSyncHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val reactionParser: ReactionFallbackParser,
    private val statsUpdater: StatsUpdater,
    private val syncLogger: SyncLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /*
     * CONFLATED channel: if a sync is running when a new notification arrives,
     * at most one follow-up run is queued. Additional notifications while that
     * queued run waits are silently dropped (they're all equivalent — "something
     * changed, go look"). This prevents O(N) queued coroutines during burst
     * delivery (e.g. the content observer firing 50 times during MMS import).
     */
    private val smsWorkChannel = Channel<Unit>(Channel.CONFLATED)
    private val mmsWorkChannel = Channel<Unit>(Channel.CONFLATED)

    /* Mutex ensures only one SMS sync and one MMS sync run at a time.
     * triggerCatchUp() and the channel consumer both contend on the same lock,
     * so they can never run the same sync path concurrently. */
    private val smsMutex = Mutex()
    private val mmsMutex = Mutex()

    init {
        // Long-lived coroutines consume channel signals one at a time.
        scope.launch { for (unit in smsWorkChannel) smsMutex.withLock { syncLatestSms() } }
        scope.launch { for (unit in mmsWorkChannel) mmsMutex.withLock { syncLatestMms() } }
        // Startup marker — visible in end-of-day log review as a process restart boundary.
        syncLogger.log(TAG, "SmsSyncHandler initialized (process start)")
    }

    private fun debugLog(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, msg) }

    companion object {
        private const val TAG = "SmsSyncHandler"
        // Must match the key used by SmsHistoryImportWorker.
        private const val PREFS_NAME = "postmark_prefs"
        private const val KEY_FIRST_SYNC_DONE = "first_sync_completed"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Signal from SmsReceiver or SmsContentObserver — coalesced into the channel. */
    fun onSmsContentChanged(@Suppress("UNUSED_PARAMETER") uri: Uri) {
        smsWorkChannel.trySend(Unit)
    }

    /** Signal from MmsReceiver or SmsContentObserver — coalesced into the channel. */
    fun onMmsContentChanged(@Suppress("UNUSED_PARAMETER") uri: Uri) {
        mmsWorkChannel.trySend(Unit)
    }

    /**
     * Awaitable catch-up pass, called by [SmsHistoryImportWorker] after the full
     * historical sync completes to pick up any messages that arrived during the
     * sync window. Waits for any in-flight channel-triggered sync to finish first
     * (via the same Mutex), then runs synchronously on the caller's coroutine.
     */
    suspend fun triggerCatchUp() {
        smsMutex.withLock { syncLatestSms() }
        mmsMutex.withLock { syncLatestMms() }
    }
    /*
     * Fetches every SMS row whose _id is greater than the highest id already
     * stored in Room. This correctly handles burst delivery (multiple messages
     * arriving in a single ContentObserver notification) and is idempotent
     * (re-running when there are no new messages returns 0 rows instantly).
     * Bails when DB is empty (initial sync not yet done) — the worker owns that pass.
     */
    private suspend fun syncLatestSms() {
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
                /* Null address is valid for WAP push, carrier service, and some OEM ROMs.
                 * Preserve the row with an empty fallback — never skip based on missing address. */
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
                    /* Sent messages arriving via incremental sync are waiting for the
                     * SmsSentDeliveryReceiver callback; start them as PENDING so the UI
                     * shows the clock icon. Received messages have no delivery tracking. */
                    deliveryStatus = if (isSent) DELIVERY_STATUS_PENDING else DELIVERY_STATUS_NONE,
                    // Incoming messages start as unread; sent messages are always read.
                    isRead = isSent
                )
            }
        }

        if (newMessages.isEmpty()) return

        debugLog("syncLatestSms: ${newMessages.size} new message(s)")
        syncLogger.log("IncrementalSms", "${newMessages.size} new message(s) after id=$maxKnownId")

        /* Separate reaction-fallback messages from real messages before persisting.
         * Reaction fallbacks (Android: "👍 to \"text\"", Apple: "Liked 'text'") must be
         * resolved to Reaction entities and never stored as visible message bubbles. */
        val (reactionMsgs, normalMessages) = newMessages.partition { reactionParser.isReactionFallback(it.body) }

        if (normalMessages.isNotEmpty()) {
            messageRepository.insertAll(normalMessages)
            normalMessages.groupBy { it.threadId }.forEach { (threadId, msgs) ->
                val latest = msgs.last()
                messageRepository.deleteOptimisticMessages(threadId)
                threadRepository.updateLastMessageAt(threadId, latest.timestamp)
                threadRepository.updateLastMessagePreview(threadId, latest.previewText)
            }
        }
        // Clean up optimistic messages for threads that only received reaction fallbacks.
        val normalThreadIds = normalMessages.map { it.threadId }.toSet()
        reactionMsgs.map { it.threadId }.distinct()
            .filter { it !in normalThreadIds }
            .forEach { messageRepository.deleteOptimisticMessages(it) }

        // One stats recompute covers all affected threads.
        statsUpdater.recomputeAll()

        /* Resolve reaction fallback messages into Reaction entities (deduped).
         * If the original message cannot be found within 100 messages, insert the
         * fallback as a normal visible bubble rather than silently dropping it. */
        val unresolvedReactionMsgs = mutableListOf<Message>()
        reactionMsgs.forEach { message ->
            val parsed = reactionParser.parse(message.body) ?: return@forEach
            val threadMessages = messageRepository.getByThread(message.threadId)
            val senderAddress = if (message.isSent) SELF_ADDRESS else message.address
            val reaction = reactionParser.processIncomingMessage(message, threadMessages, senderAddress)

            if (reaction != null) {
                if (parsed.isRemoval) {
                    messageRepository.deleteReaction(reaction.messageId, reaction.senderAddress, reaction.emoji)
                } else if (!messageRepository.reactionExists(reaction.messageId, reaction.senderAddress, reaction.emoji)) {
                    messageRepository.insertReaction(reaction)
                }
            } else {
                // Original not found — preserve as a normal visible bubble (only for additions).
                if (!parsed.isRemoval) {
                    unresolvedReactionMsgs += message
                }
            }
        }
        if (unresolvedReactionMsgs.isNotEmpty()) {
            messageRepository.insertAll(unresolvedReactionMsgs)
        }
    }

    /*
     * Mirrors syncLatestSms() but for content://mms. Bound is derived from the
     * highest stored MMS id (which is offset by MMS_ID_OFFSET) minus the offset,
     * giving the raw MMS _id to use in the WHERE clause.
     */
    private suspend fun syncLatestMms() {
        val maxStoredId = messageRepository.getMaxMmsId() ?: 0L
        if (maxStoredId <= 0L) {
            /* No MMS in Room yet. Only proceed if the full historical sync has
             * already completed — otherwise we'd run "_id > 0" concurrently with
             * the worker, fetching all historical MMS and racing on thread metadata.
             * Incoming MMS will be captured by the worker's catch-up pass at end of run,
             * or by the next incremental sync after the first MMS batch is flushed. */
            val firstSyncDone = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FIRST_SYNC_DONE, false)
            if (!firstSyncDone) {
                debugLog("syncLatestMms: no MMS in Room and first sync not complete — deferring to worker")
                syncLogger.log("IncrementalMms", "deferred — first sync not yet complete, worker owns MMS import")
                return
            }
        }
        // coerceAtLeast(0) prevents a negative bound when no MMS has been stored yet;
        // "_id > 0" correctly returns all rows because MMS IDs start at 1.
        val maxRawId = (maxStoredId - MMS_ID_OFFSET).coerceAtLeast(0L)
        debugLog("syncLatestMms: maxStoredId=$maxStoredId  maxRawId=$maxRawId")

        val cursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("_id", "thread_id", "date", "msg_box"),
            /* Whitelist only inbox (1) and sent (2).
             * Drafts (3), outbox (4), and failed (5) are excluded:
             * - Drafts are not real messages.
             * - Outbox is a transient state; the same _id flips to sent or failed.
             *   Importing it now would leave a stale "sent" bubble if it fails.
             * - Failed OS-level sends have no error UI here, so showing them as
             *   sent bubbles would be misleading. */
            "_id > ? AND ${Telephony.Mms.MESSAGE_BOX} IN (${Telephony.Mms.MESSAGE_BOX_INBOX}, ${Telephony.Mms.MESSAGE_BOX_SENT})",
            arrayOf(maxRawId.toString()),
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
                    mimeType = parts.mimeType,
                    // Incoming MMS messages start as unread; sent ones are already read.
                    isRead = isSent
                )
            }
        }

        if (newMessages.isEmpty()) return

        debugLog("syncLatestMms: ${newMessages.size} new MMS message(s)")
        val sentCount     = newMessages.count { it.isSent }
        val receivedCount = newMessages.count { !it.isSent }
        syncLogger.log("IncrementalMms", "${newMessages.size} new MMS after rawId=$maxRawId (sent=$sentCount received=$receivedCount)")

        /* Separate reaction-fallback MMS messages from real messages before persisting.
         * Reaction fallbacks (Android: "👍 to \"text\"", Apple: "Liked 'text'") must be
         * resolved to Reaction entities and never stored as visible message bubbles. */
        val (reactionMsgs, normalMessages) = newMessages.partition { reactionParser.isReactionFallback(it.body) }

        if (normalMessages.isNotEmpty()) {
            messageRepository.insertAll(normalMessages)
        }

        normalMessages.groupBy { it.threadId }.forEach { (threadId, msgs) ->
            val latest = msgs.last()

            /*
             * Transfer delivery status from the optimistic row to the just-inserted real row.
             * We transfer PENDING too (not just SENT/FAILED) so the real row keeps showing
             * a delivery indicator during the window between sync replacing the optimistic row
             * and MmsSentReceiver updating the real row with the final MMSC result.
             * Race scenario A: MmsSentReceiver fired and marked the temp row FAILED/SENT
             * before this sync ran → transfer that terminal status immediately.
             * Race scenario B: MmsSentReceiver hasn't fired yet → transfer PENDING so the
             * UI keeps showing the clock indicator instead of a blank.
             */
            val optStatus = messageRepository.getOptimisticSentDeliveryStatus(threadId)
            if (optStatus != null && optStatus != DELIVERY_STATUS_NONE) {
                val sentMsg = msgs.filter { it.isSent }.maxByOrNull { it.timestamp }
                if (sentMsg != null) {
                    messageRepository.updateDeliveryStatus(sentMsg.id, optStatus)
                    val statusName = when (optStatus) {
                        DELIVERY_STATUS_SENT    -> "SENT"
                        DELIVERY_STATUS_FAILED  -> "FAILED"
                        DELIVERY_STATUS_PENDING -> "PENDING"
                        else                    -> "status=$optStatus"
                    }
                    syncLogger.log("IncrementalMms", "transferred $statusName status to real row id=${sentMsg.id} threadId=$threadId")
                }
            }

            /*
             * Transfer the attachmentUri from the locally-cached compressed image to the
             * real row. Samsung's content://mms/part/ data for SENT rows is typically empty,
             * so we use our own filesDir cache to keep the image visible after the real row
             * replaces the optimistic one.
             *
             * Primary strategy: look for the cache file at mms_attach_<tempId>.bin using the
             * optimistic row's id (= tempId). This is immune to the race where ThreadViewModel
             * hasn't yet updated the stored attachmentUri in Room (it does so after sendMms()
             * returns, but the ContentObserver may fire before that DB update completes).
             * The cache file is written by MmsManagerWrapper BEFORE sendMultimediaMessage()
             * is called, so it is guaranteed to exist by the time the observer fires.
             *
             * Fallback: use the stored attachmentUri on the optimistic row.
             */
            val sentMsg = msgs.filter { it.isSent }.maxByOrNull { it.timestamp }
            if (sentMsg != null) {
                val optId = messageRepository.getOptimisticSentId(threadId)
                val transferUri: String? = if (optId != null) {
                    // Derive the cache file path from the tempId stored as the optimistic row id.
                    val cacheFile = File(context.filesDir, "mms_attach_$optId.bin")
                    if (cacheFile.exists()) {
                        try {
                            // Build a FileProvider URI so Coil can load it within the app process.
                            FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", cacheFile
                            ).toString()
                        } catch (_: Exception) {
                            // FileProvider lookup failed — fall back to the DB-stored URI.
                            messageRepository.getOptimisticSentAttachmentUri(threadId)
                        }
                    } else {
                        // Cache file not found — fall back to whatever URI is stored on the row.
                        messageRepository.getOptimisticSentAttachmentUri(threadId)
                    }
                } else {
                    // No optimistic row found (already deleted or never existed) — try DB URI.
                    messageRepository.getOptimisticSentAttachmentUri(threadId)
                }
                if (transferUri != null) {
                    messageRepository.updateAttachmentUri(sentMsg.id, transferUri)
                    syncLogger.log("IncrementalMms", "transferred attachmentUri to real row id=${sentMsg.id}")
                }
            }

            messageRepository.deleteOptimisticMessages(threadId)
            threadRepository.updateLastMessageAt(threadId, latest.timestamp)
            // Use emoji label for media-only MMS in the thread preview.
            val preview = latest.previewText
            threadRepository.updateLastMessagePreview(threadId, preview)
        }

        // Clean up optimistic messages for threads that only received MMS reaction fallbacks.
        val normalThreadIds = normalMessages.map { it.threadId }.toSet()
        reactionMsgs.map { it.threadId }.distinct()
            .filter { it !in normalThreadIds }
            .forEach { messageRepository.deleteOptimisticMessages(it) }

        statsUpdater.recomputeAll()

        /* Resolve MMS reaction fallback messages into Reaction entities (deduped).
         * Mirrors the same logic used in syncLatestSms(). If the original message
         * cannot be found, insert the fallback as a normal visible bubble. */
        val unresolvedReactionMsgs = mutableListOf<Message>()
        reactionMsgs.forEach { message ->
            val parsed = reactionParser.parse(message.body) ?: return@forEach
            val threadMessages = messageRepository.getByThread(message.threadId)
            val senderAddress = if (message.isSent) SELF_ADDRESS else message.address
            val reaction = reactionParser.processIncomingMessage(message, threadMessages, senderAddress)

            if (reaction != null) {
                if (parsed.isRemoval) {
                    messageRepository.deleteReaction(reaction.messageId, reaction.senderAddress, reaction.emoji)
                } else if (!messageRepository.reactionExists(reaction.messageId, reaction.senderAddress, reaction.emoji)) {
                    messageRepository.insertReaction(reaction)
                }
            } else {
                // Original not found — preserve as a normal visible bubble (only for additions).
                if (!parsed.isRemoval) {
                    unresolvedReactionMsgs += message
                }
            }
        }
        if (unresolvedReactionMsgs.isNotEmpty()) {
            messageRepository.insertAll(unresolvedReactionMsgs)
        }
    }

    // Reads text body and first media attachment from the given MMS part table.
    // Returns MmsParts with a stable content://mms/part/{id} URI for image/video/audio.
    private fun getMmsBodyIncremental(mmsId: Long): MmsParts {
        val cursor = context.contentResolver.query(
            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$mmsId/part"),
            arrayOf("_id", Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
            null, null, null
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
            val ctIdx   = it.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
            val textIdx = it.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)
            while (it.moveToNext()) {
                val ct     = it.getString(ctIdx) ?: continue
                val partId = it.getLong(idIdx)
                when {
                    ct.equals("text/plain", ignoreCase = true) ->
                        sb.append(it.getString(textIdx) ?: "")
                    ct.equals("application/smil", ignoreCase = true) -> Unit
                    ct.startsWith("image/", ignoreCase = true) ||
                    ct.startsWith("video/", ignoreCase = true) ||
                    ct.startsWith("audio/", ignoreCase = true) -> {
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
        // 137 = PduHeaders.FROM (sender of a received MMS)
        // 151 = PduHeaders.TO   (recipient of a sent MMS)
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
