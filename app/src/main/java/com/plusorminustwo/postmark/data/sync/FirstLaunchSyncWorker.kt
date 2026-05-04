package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.plusorminustwo.postmark.BuildConfig
import com.plusorminustwo.postmark.PostmarkApplication
import com.plusorminustwo.postmark.R
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
    // MMS-specific debug logs — filter logcat with: adb logcat -s PostmarkMms
    private fun debugMmsLog(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG_MMS, msg) }

    // Builds the sync notification. When total > 0 shows a determinate progress bar;
    // when total == 0 shows an indeterminate spinner.
    private fun buildSyncNotification(text: String, done: Int = 0, total: Int = 0): android.app.Notification =
        NotificationCompat.Builder(applicationContext, PostmarkApplication.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Syncing messages")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .build()

    // Wraps a notification into ForegroundInfo with the correct foreground service type.
    private fun buildForegroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID_SYNC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID_SYNC, notification)
        }

    // Promotes this worker to a foreground service so the OS will not kill it
    // during large SMS/MMS syncs on Samsung and other aggressive OEM ROMs.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(buildSyncNotification("Importing your SMS and MMS history\u2026"))

    // Updates the foreground notification text and progress bar.
    // Called every 500 rows during the MMS per-row query phase and after each SMS persist batch.
    private suspend fun postProgress(phase: String, done: Int, total: Int) {
        val text = if (total > 0) "$phase \u2014 ${"%,d".format(done)} / ${"%,d".format(total)}"
                   else phase
        setForeground(buildForegroundInfo(buildSyncNotification(text, done, total)))
    }

    override suspend fun doWork(): Result {
        // Elevate to foreground service immediately — prevents the OS (especially
        // Samsung OneUI battery optimisation) from killing the worker mid-sync.
        setForeground(getForegroundInfo())
        debugLog("doWork() started — attempt ${runAttemptCount + 1}")
        return try {
            postProgress("Syncing SMS\u2026", 0, 0)
            val smsResult = syncAllSms { done, total -> postProgress("Syncing SMS", done, total) }
            if (smsResult.threadCount < 0) {
                val msg = "SMS cursor was null — provider unavailable or permission denied"
                Log.w(TAG, msg)
                writeStatus("Failed: $msg")
                return if (runAttemptCount < 3) Result.retry()
                else Result.failure(workDataOf(KEY_ERROR to msg))
            }
            postProgress("Syncing MMS\u2026", 0, 0)
            val mmsCount = syncAllMms { done, total -> postProgress("Syncing MMS", done, total) }
            postProgress("Wrapping up\u2026", 0, 0)
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

    private suspend fun syncAllSms(onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): SyncResult {
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
        val smsTotal = messages.size
        messages.chunked(500).forEachIndexed { idx, chunk ->
            messageRepository.insertAll(chunk)
            // Report progress after each 500-message batch so the notification updates
            // smoothly as the DB writes complete.
            onProgress(minOf((idx + 1) * 500, smsTotal), smsTotal)
        }
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
    private suspend fun syncAllMms(onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Int {
        debugMmsLog("Querying content://mms …")
        val threads  = mutableMapOf<Long, Thread>()
        val messages = mutableListOf<Message>()
        val mmsProjection = arrayOf("_id", "thread_id", "date", "msg_box")

        // ── Primary query ─────────────────────────────────────────────────────
        val primaryCursor = applicationContext.contentResolver.query(
            Uri.parse("content://mms"), mmsProjection, null, null, "date ASC"
        )
        val primaryRowCount = primaryCursor?.count ?: -1
        debugMmsLog("syncAllMms: primary cursor: ${if (primaryCursor == null) "NULL" else "$primaryRowCount rows"}")

        if (primaryRowCount > 0) {
            // Happy path: primary URI returned rows — use them directly.
            primaryCursor!!.use { processMmsCursor(it, threads, messages, primaryRowCount, onProgress) }
        } else {
            // ── Samsung / OEM fallback ────────────────────────────────────────
            // Samsung OneUI returns a null or empty cursor for content://mms
            // despite READ_SMS being granted — the same failure mode as
            // content://sms (see syncAllSms). The per-mailbox sub-URIs remain
            // readable and return the actual messages.
            primaryCursor?.close()
            Log.w(TAG_MMS, "MMS primary cursor returned $primaryRowCount rows — trying mailbox fallback URIs")
            listOf(
                Uri.parse("content://mms/inbox"),
                Uri.parse("content://mms/sent"),
            ).forEach { uri ->
                val c = applicationContext.contentResolver.query(
                    uri, mmsProjection, null, null, "date ASC"
                )
                debugMmsLog("MMS fallback $uri → ${c?.count ?: "null"} rows")
                // Pass each fallback cursor's own count as the total so progress
                // resets per-URI — acceptable for the Samsung edge case.
                c?.use { processMmsCursor(it, threads, messages, it.count, onProgress) }
            }
        }

        if (messages.isEmpty()) {
            debugMmsLog("No MMS messages found")
            return 0
        }

        Log.i(TAG_MMS, "MMS collected ${threads.size} threads, ${messages.size} messages — persisting …")

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
        Log.i(TAG_MMS, "MMS persist complete")

        return messages.size
    }

    // Extracts MMS rows from a cursor into the shared threads map and messages list.
    // Called once for the primary URI or once per fallback URI (inbox/sent).
    // Mirrors processSmsCursor() for symmetry with the SMS fallback path.
    // Fires onProgress every 500 rows (and once at the end) so the notification stays live
    // during the slow per-row getMmsBody + getMmsAddress sub-queries.
    private suspend fun processMmsCursor(
        cursor: Cursor,
        threads: MutableMap<Long, Thread>,
        messages: MutableList<Message>,
        total: Int = 0,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ) {
        val idIdx     = cursor.getColumnIndexOrThrow("_id")
        val threadIdx = cursor.getColumnIndexOrThrow("thread_id")
        val dateIdx   = cursor.getColumnIndexOrThrow("date")
        val boxIdx    = cursor.getColumnIndexOrThrow("msg_box")

        var done = 0
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
            debugMmsLog("processMmsCursor: rawId=$rawId  mimeType=${parts.mimeType}  attachmentUri=${parts.attachmentUri}")

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
            done++
            // Fire progress every 500 rows; the final partial batch is reported after the loop.
            if (done % 500 == 0) onProgress(done, total)
        }
        onProgress(done, total) // final update with exact count
    }

    // Reads all parts of an MMS message and returns the text body plus the content URI
    // of the first media attachment (image, video, or audio). The URI points to
    // content://mms/part/{partId} which is stable and readable by the default SMS app.
    // Returns MmsParts("[MMS]", null, null) when the cursor is unavailable.
    private fun getMmsBody(mmsId: Long): MmsParts {
        val cursor = applicationContext.contentResolver.query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf("_id", "ct", "text"), null, null, null
        ) ?: run {
            Log.w(TAG_MMS, "getMmsBody: parts cursor null for mmsId=$mmsId")
            return MmsParts("[MMS]", null, null)
        }
        debugMmsLog("getMmsBody: mmsId=$mmsId  partCount=${cursor.count}")
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
                    ct.equals("text/plain", ignoreCase = true) ->
                        sb.append(it.getString(textIdx) ?: "")
                    // Skip SMIL layout descriptor — not user-visible content.
                    ct.equals("application/smil", ignoreCase = true) -> Unit
                    // Store the first image/video/audio part URI (case-insensitive for
                    // Samsung and other OEMs that use mixed-case MIME types like audio/AMR).
                    ct.startsWith("image/", ignoreCase = true) ||
                    ct.startsWith("video/", ignoreCase = true) ||
                    ct.startsWith("audio/", ignoreCase = true) -> {
                        debugMmsLog("getMmsBody: found media part ct=$ct  partId=$partId")
                        if (attachmentUri == null) {
                            attachmentUri = "content://mms/part/$partId"
                            mimeType = ct
                        }
                    }
                    else -> debugMmsLog("getMmsBody: skipping unknown part ct=$ct")
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
        private const val TAG          = "PostmarkSync"
        private const val TAG_MMS      = "PostmarkMms"
        private const val PREFS        = "postmark_prefs"
        // Notification ID for the foreground-service progress notification.
        // Must be > 0 and distinct from SMS notification IDs (which are sender hashCodes
        // or Int.MIN_VALUE for the summary).
        private const val NOTIF_ID_SYNC = 1_001

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
