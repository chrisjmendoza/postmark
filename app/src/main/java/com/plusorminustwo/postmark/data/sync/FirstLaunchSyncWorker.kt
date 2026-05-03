package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.plusorminustwo.postmark.BuildConfig
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.search.parser.AppleReactionParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FirstLaunchSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val reactionParser: AppleReactionParser,
    private val statsUpdater: StatsUpdater,
    private val smsSyncHandler: SmsSyncHandler  // for post-sync catch-up of race-window messages
) : CoroutineWorker(context, params) {

    // Verbose debug logs only appear in debug builds; warnings and errors always fire.
    private fun debugLog(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, msg) }

    override suspend fun doWork(): Result {
        debugLog("doWork() started — attempt ${runAttemptCount + 1}")
        return try {
            val smsResult = syncAllSms()
            if (smsResult.threadCount < 0) {
                val msg = "SMS cursor was null — provider unavailable or permission denied"
                Log.w(TAG, msg)
                writeStatus("Failed: $msg")
                return if (runAttemptCount < 3) Result.retry()
                else Result.failure(workDataOf(KEY_ERROR to msg))
            }
            val mmsCount = syncAllMms()
            val status = "OK: ${smsResult.threadCount} threads, ${smsResult.messageCount} SMS + $mmsCount MMS"
            Log.i(TAG, "Sync complete — $status")
            writeStatus(status)
            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("first_sync_completed", true).apply()
            // One final incremental pass catches any SMS/MMS that arrived during the sync window
            // (i.e. after the cursor was opened but before the first DB commit).
            smsSyncHandler.triggerCatchUp()
            Result.success(workDataOf(KEY_STATUS to status))
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Sync exception — $msg", e)
            writeStatus("Error: $msg")
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to msg))
        }
    }

    private suspend fun syncAllSms(): SyncResult {
        debugLog("Device: ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
        debugLog("Querying content://sms …")

        val threads = mutableMapOf<Long, Thread>()
        val messages = mutableListOf<Message>()

        // ── Primary query ─────────────────────────────────────────────────────
        val primaryCursor = applicationContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI, SMS_PROJECTION, null, null, "${Telephony.Sms.DATE} ASC"
        )
        val primaryRowCount = primaryCursor?.count ?: -1
        debugLog("Primary cursor result: ${if (primaryCursor == null) "NULL" else "$primaryRowCount rows"}")

        if (primaryRowCount > 0) {
            // Happy path: primary URI returned rows — use them directly.
            debugLog("Using primary cursor ($primaryRowCount rows)")
            primaryCursor!!.use { processSmsCursor(it, threads, messages) }
        } else {
            // ── Samsung / OEM fallback ────────────────────────────────────────
            // Two known failure modes:
            //   1. primaryCursor == null  → READ_SMS not fully effective on this ROM
            //   2. primaryCursor.count == 0 → Samsung OneUI silently returns an
            //      empty cursor for content://sms even with READ_SMS granted.
            // In both cases the per-mailbox sub-URIs are readable and return the
            // actual messages, so we always fall through to them here.
            primaryCursor?.close()
            Log.w(TAG, "Primary cursor returned $primaryRowCount rows — trying mailbox fallback URIs")

            val fallbackUris = listOf(
                Uri.parse("content://sms/inbox"),
                Uri.parse("content://sms/sent"),
                Uri.parse("content://sms/draft"),
                // outbox (type=4) and failed (type=5) are missing from some Samsung builds
                Uri.parse("content://sms/outbox"),
                Uri.parse("content://sms/failed"),
            )
            var anyNonNull = false
            fallbackUris.forEach { uri ->
                val c = applicationContext.contentResolver.query(
                    uri, SMS_PROJECTION, null, null, "${Telephony.Sms.DATE} ASC"
                )
                debugLog("Fallback $uri → ${c?.count ?: "null"} rows")
                if (c != null) {
                    anyNonNull = true
                    c.use { processSmsCursor(it, threads, messages) }
                }
            }
            if (!anyNonNull) {
                Log.e(TAG, "All SMS URIs returned null — permission denied or provider unavailable")
                return SyncResult(-1, -1)
            }
        }

        Log.i(TAG, "Sync collected ${threads.size} threads, ${messages.size} messages — persisting …")
        if (threads.isEmpty()) {
            Log.w(TAG, "0 threads collected — DB will remain empty. Check READ_SMS permission and default SMS role.")
        }
        threadRepository.upsertAll(threads.values.toList())
        messages.chunked(500).forEach { chunk -> messageRepository.insertAll(chunk) }
        Log.i(TAG, "Persist complete")

        debugLog("Running reaction parser …")
        threads.keys.forEach { threadId ->
            val threadMsgs = messageRepository.getByThread(threadId)
            threadMsgs.forEach { msg ->
                val parsed = reactionParser.parse(msg.body) ?: return@forEach
                if (!parsed.isRemoval) {
                    val reaction = reactionParser.processIncomingMessage(msg, threadMsgs, msg.address)
                    if (reaction != null) messageRepository.insertReaction(reaction)
                }
            }
        }

        debugLog("Computing thread stats …")
        statsUpdater.recomputeAll()

        return SyncResult(threads.size, messages.size)
    }

    // ── Cursor row extractor ──────────────────────────────────────────────────
    // Called once for the primary URI or once per fallback URI (inbox/sent/draft).
    // Merges rows into the shared threads map and messages list.
    private fun processSmsCursor(
        cursor: Cursor,
        threads: MutableMap<Long, Thread>,
        messages: MutableList<Message>
    ) {
        val idIdx      = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
        val threadIdx  = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
        val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val bodyIdx    = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val dateIdx    = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val typeIdx    = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

        while (cursor.moveToNext()) {
            val id       = cursor.getLong(idIdx)
            val threadId = cursor.getLong(threadIdx)
            // Null address is valid for WAP push, carrier service messages, and some OEM ROMs.
            // Preserve the row with an empty fallback — never skip a message just because the
            // address field is missing.
            val address  = cursor.getString(addressIdx) ?: ""
            val body     = cursor.getString(bodyIdx) ?: ""
            val date     = cursor.getLong(dateIdx)
            val type     = cursor.getInt(typeIdx)
            // Drafts (3), outbox (4), and failed sends (5) are all outgoing — only inbox (1) is
            // received. Using != INBOX rather than == SENT prevents left-bubble display for them.
            val isSent   = type != Telephony.Sms.MESSAGE_TYPE_INBOX

            if (!threads.containsKey(threadId)) {
                val displayName = lookupContactName(address) ?: address.ifEmpty { "Unknown" }
                threads[threadId] = Thread(
                    id = threadId,
                    displayName = displayName,
                    address = address,
                    lastMessageAt = date,
                    lastMessagePreview = body,
                    backupPolicy = BackupPolicy.GLOBAL
                )
            } else {
                val existing = threads[threadId]!!
                if (date > existing.lastMessageAt) {
                    threads[threadId] = existing.copy(lastMessageAt = date, lastMessagePreview = body)
                }
            }

            messages.add(Message(id, threadId, address, body, date, isSent, type))
        }
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
            applicationContext.contentResolver.query(
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

    // ── MMS historical sync ───────────────────────────────────────────────────
    // Queries content://mms for all stored MMS messages. Runs after syncAllSms()
    // so that any SMS-originated threads are already in Room; MMS-only threads
    // are inserted here, and existing threads are updated if MMS has a newer
    // last message. Returns the count of MMS messages persisted.
    private suspend fun syncAllMms(): Int {
        debugLog("Querying content://mms …")
        val mmsCursor = applicationContext.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "thread_id", "date", "msg_box"),
            null, null, "date ASC"
        ) ?: run {
            Log.w(TAG, "MMS cursor was null — skipping MMS sync")
            return 0
        }

        val threads  = mutableMapOf<Long, Thread>()
        val messages = mutableListOf<Message>()

        mmsCursor.use { cursor ->
            val idIdx     = cursor.getColumnIndexOrThrow("_id")
            val threadIdx = cursor.getColumnIndexOrThrow("thread_id")
            val dateIdx   = cursor.getColumnIndexOrThrow("date")
            val boxIdx    = cursor.getColumnIndexOrThrow("msg_box")

            while (cursor.moveToNext()) {
                val rawId     = cursor.getLong(idIdx)
                val threadId  = cursor.getLong(threadIdx)
                val dateSec   = cursor.getLong(dateIdx)
                val msgBox    = cursor.getInt(boxIdx)
                val id        = MMS_ID_OFFSET + rawId
                // Drafts (3) and outbox (4) are outgoing; only inbox (1) is received.
                val isSent    = msgBox != Telephony.Mms.MESSAGE_BOX_INBOX
                val timestamp = dateSec * 1000L          // seconds → millis
                val parts     = getMmsBody(rawId)
                val address   = getMmsAddress(rawId, isSent)

                val existing = threads[threadId]
                if (existing == null || timestamp > existing.lastMessageAt) {
                    val displayName = lookupContactName(address) ?: address
                    threads[threadId] = Thread(
                        id = threadId,
                        displayName = displayName,
                        address = address,
                        lastMessageAt = timestamp,
                        // Use emoji label for media-only messages in the preview.
                        lastMessagePreview = parts.previewText(),
                        backupPolicy = BackupPolicy.GLOBAL
                    )
                }

                messages += Message(
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

        if (messages.isEmpty()) {
            debugLog("No MMS messages found")
            return 0
        }

        Log.i(TAG, "MMS collected ${threads.size} threads, ${messages.size} messages — persisting …")

        // For each MMS thread: insert if new, or update lastMessageAt/Preview if newer.
        threads.forEach { (threadId, mmsThread) ->
            val inRoom = threadRepository.getById(threadId)
            if (inRoom == null) {
                threadRepository.upsert(mmsThread)
            } else if (mmsThread.lastMessageAt > inRoom.lastMessageAt) {
                threadRepository.updateLastMessageAt(threadId, mmsThread.lastMessageAt)
                threadRepository.updateLastMessagePreview(threadId, mmsThread.lastMessagePreview)
            }
        }

        messages.chunked(500).forEach { chunk -> messageRepository.insertAll(chunk) }
        Log.i(TAG, "MMS persist complete")

        return messages.size
    }

    // Reads all parts of an MMS message and returns the text body plus the content URI
    // of the first media attachment (image, video, or audio). The URI points to
    // content://mms/part/{partId} which is stable and readable by the default SMS app.
    // Returns MmsParts("[MMS]", null, null) when the cursor is unavailable.
    private fun getMmsBody(mmsId: Long): MmsParts {
        val cursor = applicationContext.contentResolver.query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf("_id", "ct", "text"), null, null, null
        ) ?: return MmsParts("[MMS]", null, null)
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
                    // Accumulate text body from all text/plain parts.
                    ct == "text/plain" ->
                        sb.append(it.getString(textIdx) ?: "")
                    // Skip SMIL layout descriptor — not user-visible content.
                    ct == "application/smil" -> Unit
                    // Store the first image/video/audio part URI.
                    ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") -> {
                        if (attachmentUri == null) {
                            attachmentUri = "content://mms/part/$partId"
                            mimeType = ct
                        }
                    }
                }
            }
        }
        // If there’s no text and no recognised media, fall back to the [MMS] placeholder.
        val body = sb.toString().trim()
        return MmsParts(
            body = body,
            attachmentUri = attachmentUri,
            mimeType = mimeType
        )
    }

    // Returns the relevant address for an MMS message.
    // Received messages use PDU type 137 (FROM); sent messages use type 151 (TO).
    // Falls back to "Unknown" if the address table has no matching row.
    private fun getMmsAddress(mmsId: Long, isSent: Boolean): String {
        // 137 = PduHeaders.FROM (sender of received MMS)
        // 151 = PduHeaders.TO   (recipient of sent MMS)
        val addrType = if (isSent) 151 else 137
        val cursor = applicationContext.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            "type = ?", arrayOf(addrType.toString()), null
        ) ?: return "Unknown"
        return cursor.use {
            if (it.moveToFirst()) {
                val addr = it.getString(it.getColumnIndexOrThrow("address")) ?: "Unknown"
                // "insert-address-token" is a Samsung PDU placeholder written before the real FROM
                // address is resolved. Treat it as unknown to avoid persisting garbage display names.
                if (addr == "insert-address-token") "Unknown" else addr
            } else "Unknown"
        }
    }

    private fun writeStatus(status: String) {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATUS, status).apply()
    }

    private data class SyncResult(val threadCount: Int, val messageCount: Int)

    // Carries the extracted text body and optional media attachment info for one MMS PDU.
    private data class MmsParts(
        val body: String,
        val attachmentUri: String?,
        val mimeType: String?
    ) {
        // Human-readable thread preview: emoji label for media-only messages.
        fun previewText(): String = when {
            body.isNotEmpty()                      -> body
            mimeType?.startsWith("image/") == true -> "📷 Photo"
            mimeType?.startsWith("video/") == true -> "🎥 Video"
            mimeType?.startsWith("audio/") == true -> "🎵 Audio message"
            else                                   -> "[MMS]"
        }
    }

    companion object {
        const val WORK_NAME = "first_launch_sms_sync"
        const val KEY_STATUS = "last_sync_status"
        const val KEY_ERROR  = "last_sync_error"
        private const val TAG   = "PostmarkSync"
        private const val PREFS = "postmark_prefs"

        // Shared projection used for both the primary URI and Samsung fallback URIs.
        internal val SMS_PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        )

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<FirstLaunchSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
    }
}
