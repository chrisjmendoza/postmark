package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import com.plusorminustwo.postmark.ui.MainActivity
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-time [CoroutineWorker] that performs the full historical SMS/MMS import on
 * first launch (or after a sync-recovery reset).
 *
 * Reads every thread and message from the system content providers, writes them to
 * Room, then — only after BOTH the SMS and MMS imports have persisted — resolves
 * reaction fallback messages via [ReactionResolver] and triggers a stats recompute.
 * After the bulk import completes it calls [SmsSyncHandler.triggerCatchUp] to pick
 * up any messages that arrived during the import window.
 *
 * Runs as a foreground worker (shows a persistent notification with progress).
 */
@HiltWorker
class SmsHistoryImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val reactionResolver: ReactionResolver,
    private val statsUpdater: StatsUpdater,
    private val smsSyncHandler: SmsSyncHandler,  // for post-sync catch-up of race-window messages
    private val syncLogger: SyncLogger
) : CoroutineWorker(context, params) {

    // Verbose debug logs only appear in debug builds; warnings and errors always fire.
    private fun debugLog(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, msg) }
    // MMS-specific debug logs — filter logcat with: adb logcat -s PostmarkMms
    private fun debugMmsLog(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG_MMS, msg) }

    // Builds the sync notification. When total > 0 shows a determinate progress bar;
    // when total == 0 shows an indeterminate spinner.
    private fun buildSyncNotification(text: String, done: Int = 0, total: Int = 0): android.app.Notification {
        val openAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(applicationContext, PostmarkApplication.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Syncing messages")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .setContentIntent(pendingIntent)
            .build()
    }

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
    // [eta] is an optional estimated time remaining string, e.g. "~3m 12s".
    private suspend fun postProgress(phase: String, done: Int, total: Int, eta: String = "") {
        val base = if (total > 0) "$phase \u2014 ${"%,d".format(done)} / ${"%,d".format(total)}"
                   else phase
        val text = if (eta.isNotEmpty()) "$base ($eta)" else base
        setForeground(buildForegroundInfo(buildSyncNotification(text, done, total)))
        // Also push progress to WorkManager so the UI can display it without polling the notification.
        setProgress(workDataOf(
            KEY_PROGRESS_PHASE to phase,
            KEY_PROGRESS_DONE  to done,
            KEY_PROGRESS_TOTAL to total,
            KEY_PROGRESS_ETA   to eta
        ))
    }

    override suspend fun doWork(): Result {
        // Elevate to foreground service immediately — prevents the OS (especially
        // Samsung OneUI battery optimisation) from killing the worker mid-sync.
        setForeground(getForegroundInfo())
        debugLog("doWork() started — attempt ${runAttemptCount + 1}")
        syncLogger.log("Sync", "doWork() started — attempt ${runAttemptCount + 1}")
        return try {
            postProgress("Syncing SMS\u2026", 0, 0)
            val smsResult = syncAllSms { done, total -> postProgress("Syncing SMS", done, total) }
            if (smsResult.threadCount < 0) {
                val msg = "SMS cursor was null — provider unavailable or permission denied"
                Log.w(TAG, msg)
                syncLogger.logError("Sync", msg)
                writeStatus("Failed: $msg")
                return if (runAttemptCount < 3) Result.retry()
                else Result.failure(workDataOf(KEY_ERROR to msg))
            }
            syncLogger.log("Sync", "SMS done: ${smsResult.threadCount} threads, ${smsResult.messageCount} messages")
            postProgress("Syncing MMS\u2026", 0, 0)
            val mmsCount = syncAllMms { done, total, eta -> postProgress("Syncing MMS", done, total, eta) }
            // Resolve reaction fallbacks only now that BOTH transports are fully persisted.
            // An SMS reaction quoting an MMS-originated message (or a fallback that itself
            // arrived as MMS) can only match once the candidate pool spans SMS and MMS;
            // running this inside syncAllSms() (as before) permanently missed those.
            postProgress("Resolving reactions\u2026", 0, 0)
            val reactions = reactionResolver.resolveAll()
            syncLogger.log("Sync", "Reactions: ${reactions.inserted} resolved, ${reactions.removed} fallbacks removed")
            postProgress("Wrapping up\u2026", 0, 0)
            statsUpdater.recomputeAll()
            val status = "OK: ${smsResult.threadCount} threads, ${smsResult.messageCount} SMS + $mmsCount MMS"
            Log.i(TAG, "Sync complete — $status")
            syncLogger.log("Sync", "Complete: $status")
            writeStatus(status)
            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("first_sync_completed", true).apply()
            // One final incremental pass catches any SMS/MMS that arrived during the sync window
            // (i.e. after the cursor was opened but before the first DB commit).
            smsSyncHandler.triggerCatchUp()
            syncLogger.log("Sync", "Catch-up pass complete")
            Result.success(workDataOf(KEY_STATUS to status))
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Sync exception — $msg", e)
            syncLogger.logError("Sync", "Exception during sync: $msg", e)
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
            // Supplement with content://sms/sent — post-June 2026 Android update excludes
            // sent messages from the aggregate URI even when it returns a non-null cursor.
            // Duplicates with the primary cursor are resolved by ID before persisting.
            applicationContext.contentResolver.query(
                Uri.parse("content://sms/sent"), SMS_PROJECTION, null, null, "${Telephony.Sms.DATE} ASC"
            )?.use { processSmsCursor(it, threads, messages) }
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
        // Create any threads that don't yet exist in Room. IGNORE conflict means we never
        // overwrite an existing row — user settings (isPinned, isMuted, notificationsEnabled)
        // are preserved across re-syncs. Metadata (lastMessageAt / preview) is then updated
        // via targeted UPDATE queries below, which also fire for threads that already existed.
        threadRepository.insertIgnoreAll(threads.values.toList())
        threads.values.forEach { thread ->
            threadRepository.updateLastMessageAt(thread.id, thread.lastMessageAt)
            threadRepository.updateLastMessagePreview(thread.id, thread.lastMessagePreview)
        }
        // Dedup by ID: content://sms and content://sms/sent may contain overlapping rows.
        val seenMsgIds = mutableSetOf<Long>()
        val dedupIter = messages.iterator()
        while (dedupIter.hasNext()) { if (!seenMsgIds.add(dedupIter.next().id)) dedupIter.remove() }

        val smsTotal = messages.size
        messages.chunked(500).forEachIndexed { idx, chunk ->
            messageRepository.insertAll(chunk)
            // Report progress after each 500-message batch so the notification updates
            // smoothly as the DB writes complete.
            onProgress(minOf((idx + 1) * 500, smsTotal), smsTotal)
        }
        Log.i(TAG, "Persist complete")
        // Reaction fallbacks are NOT resolved here — doWork() runs ReactionResolver once
        // after syncAllMms() so the candidate pool spans both transports.

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
    private suspend fun syncAllMms(onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> }): Int {
        debugMmsLog("Querying content://mms …")
        val threads = mutableMapOf<Long, Thread>()
        var totalMmsCount = 0
        val mmsProjection = arrayOf("_id", "thread_id", "date", "msg_box")
        val filter = "msg_box NOT IN (3, 5)"
        val sortDesc = "_id DESC"

        // Checkpoint resume: skip rows already imported in a prior run.
        val resumeBeforeRawId: Long = (messageRepository.getMinMmsId() ?: 0L)
            .let { if (it > 0) it - MMS_ID_OFFSET else Long.MAX_VALUE }
        if (resumeBeforeRawId < Long.MAX_VALUE) {
            Log.i(TAG_MMS, "syncAllMms: resuming, fast-skipping rawId >= $resumeBeforeRawId")
        }

        // Phase 1: newest 1000 rows so the UI has content quickly.
        val phase1Cursor = applicationContext.contentResolver.query(
            Telephony.Mms.CONTENT_URI, mmsProjection, filter, null, "$sortDesc LIMIT 1000"
        )

        if (phase1Cursor == null) {
            // Samsung / OEM fallback
            Log.w(TAG_MMS, "MMS primary cursor null — trying mailbox fallback URIs")
            listOf("content://mms/inbox", "content://mms/sent", "content://mms/outbox").forEach { uriStr ->
                applicationContext.contentResolver.query(
                    Uri.parse(uriStr), mmsProjection, filter, null, sortDesc
                )?.use { totalMmsCount += processMmsCursor(it, threads, it.count, resumeBeforeRawId, onProgress).inserted }
            }
            return finaliseThreadMetadata(threads, totalMmsCount)
        }

        val phase1Result = phase1Cursor.use { processMmsCursor(it, threads, 1000, resumeBeforeRawId) }
        totalMmsCount += phase1Result.inserted
        val phase2BelowRawId = if (phase1Result.minRawId < Long.MAX_VALUE) phase1Result.minRawId else resumeBeforeRawId

        postProgress("Recent messages loaded — loading full history…", 0, 0)

        // Phase 2: everything older.
        if (phase2BelowRawId > 0L && phase2BelowRawId < Long.MAX_VALUE) {
            applicationContext.contentResolver.query(
                Telephony.Mms.CONTENT_URI, mmsProjection,
                "$filter AND _id < ?", arrayOf(phase2BelowRawId.toString()), sortDesc
            )?.use { totalMmsCount += processMmsCursor(it, threads, it.count, resumeBeforeRawId, onProgress).inserted }
        }

        return finaliseThreadMetadata(threads, totalMmsCount)
    }

    private suspend fun finaliseThreadMetadata(threads: MutableMap<Long, Thread>, count: Int): Int {
        if (count == 0) { debugMmsLog("No MMS messages found"); return 0 }
        Log.i(TAG_MMS, "MMS sync complete — $count messages across ${threads.size} threads")
        threads.forEach { (threadId, mmsThread) ->
            val inRoom = threadRepository.getById(threadId)
            if (inRoom == null) threadRepository.upsert(mmsThread)
            else if (mmsThread.lastMessageAt > inRoom.lastMessageAt) {
                threadRepository.updateLastMessageAt(threadId, mmsThread.lastMessageAt)
                threadRepository.updateLastMessagePreview(threadId, mmsThread.lastMessagePreview)
            }
        }
        return count
    }


    // Extracts MMS rows from a cursor, streaming inserts to Room every 500 rows so messages
    // appear in the UI progressively rather than after the entire cursor is exhausted.
    // Rows with rawId >= resumeBeforeRawId are fast-skipped (no sub-queries) to support
    // checkpoint resume when the worker is killed and restarted. Since we import newest-first
    // (_id DESC), those rows were already persisted in a prior run.
    // Returns the count of newly inserted messages (skipped rows not counted).
    private suspend fun processMmsCursor(
        cursor: Cursor,
        threads: MutableMap<Long, Thread>,
        total: Int = 0,
        resumeBeforeRawId: Long = Long.MAX_VALUE,
        onProgress: suspend (Int, Int, String) -> Unit = { _, _, _ -> }
    ): CursorResult {
        val idIdx     = cursor.getColumnIndexOrThrow("_id")
        val threadIdx = cursor.getColumnIndexOrThrow("thread_id")
        val dateIdx   = cursor.getColumnIndexOrThrow("date")
        val boxIdx    = cursor.getColumnIndexOrThrow("msg_box")

        val startTimeMs = System.currentTimeMillis()
        val pendingMessages = mutableListOf<Message>()
        // Tracks which thread IDs have already been ensured in Room this cursor pass
        // so we only call getById once per new thread (foreign key guard).
        val persistedThreadIds = mutableSetOf<Long>()
        // walked = all rows visited (skip + new); inserted = only newly persisted rows.
        var walked = 0
        var inserted = 0
        var minRawId = Long.MAX_VALUE
        val resuming = resumeBeforeRawId < Long.MAX_VALUE

        while (cursor.moveToNext()) {
            val rawId     = cursor.getLong(idIdx)
            val threadId  = cursor.getLong(threadIdx)
            val dateSec   = cursor.getLong(dateIdx)
            val msgBox    = cursor.getInt(boxIdx)
            val id        = MMS_ID_OFFSET + rawId
            val isSent    = msgBox != Telephony.Mms.MESSAGE_BOX_INBOX
            val timestamp = dateSec * 1000L
            walked++

            if (rawId >= resumeBeforeRawId) {
                // Fast skip: already in Room (imported in a prior run, newest-first).
                if (threads[threadId] == null) {
                    threads[threadId] = Thread(
                        id = threadId, displayName = "", address = "",
                        lastMessageAt = timestamp, lastMessagePreview = "",
                        backupPolicy = BackupPolicy.GLOBAL
                    )
                }
                persistedThreadIds += threadId
                if (walked % 500 == 0) onProgress(walked, total, "Resuming\u2026")
                continue
            }

            // Track the lowest rawId seen — used as the phase-2 boundary in syncAllMms.
            if (rawId < minRawId) minRawId = rawId

            // New row — run the slow sub-queries and queue for insert.
            val parts   = getMmsBody(rawId)
            val address = getMmsAddress(rawId, isSent)
            debugMmsLog("processMmsCursor: rawId=$rawId  attachments=${parts.attachments.size}  firstMime=${parts.attachments.firstOrNull()?.mimeType}")

            val existing = threads[threadId]
            if (existing == null || timestamp > existing.lastMessageAt) {
                // Only queried on the (rare) message that actually creates/updates a thread's
                // metadata, not on every row — same cost profile as before for the common case.
                val roster = getMmsParticipants(rawId)
                // Group MMS: comma-join contact names so the thread list/top bar show
                // everyone without any UI change (MMS_AUDIT §2.3).
                val displayName = if (roster.size > 1) {
                    roster.joinToString(", ") { lookupContactName(it) ?: it }
                } else {
                    lookupContactName(address) ?: address
                }
                threads[threadId] = Thread(
                    id = threadId,
                    displayName = displayName,
                    address = address,
                    lastMessageAt = timestamp,
                    lastMessagePreview = parts.previewText,
                    backupPolicy = BackupPolicy.GLOBAL,
                    participants = if (roster.size > 1) roster else emptyList()
                )
            }

            pendingMessages += Message(
                id = id,
                threadId = threadId,
                address = address,
                body = parts.body,
                timestamp = timestamp,
                isSent = isSent,
                type = msgBox,
                isMms = true,
                attachments = parts.attachments
            )
            inserted++
            if (inserted % 500 == 0) {
                flushMmsBatch(pendingMessages, persistedThreadIds, threads)
                val eta = computeEta(System.currentTimeMillis() - startTimeMs, inserted,
                    if (resuming) total - (walked - inserted) else total)
                onProgress(walked, total, eta)
            }
        }
        // Flush any remaining rows in the final partial batch.
        if (pendingMessages.isNotEmpty()) flushMmsBatch(pendingMessages, persistedThreadIds, threads)
        onProgress(walked, total, "") // final update with exact count
        return CursorResult(inserted, minRawId)
    }

    // Ensures all threads referenced by [pending] exist in Room (required by FK constraint),
    // then batch-inserts the messages and clears the list.
    private suspend fun flushMmsBatch(
        pending: MutableList<Message>,
        persistedThreadIds: MutableSet<Long>,
        threads: Map<Long, Thread>
    ) {
        val newThreadIds = pending.map { it.threadId }.toSet() - persistedThreadIds
        newThreadIds.forEach { threadId ->
            val mmsThread = threads[threadId]
            val inRoom = threadRepository.getById(threadId)
            if (inRoom == null) {
                // MMS-only thread — insert it now so the FK constraint is satisfied.
                mmsThread?.let { threadRepository.upsert(it) }
            } else if (mmsThread != null && mmsThread.lastMessageAt > inRoom.lastMessageAt) {
                // Thread exists (from SMS sync). Since we import DESC, the first time we see
                // a thread its mmsThread entry already holds the newest MMS timestamp — push
                // it to Room now so the conversation bubbles up during streaming, not just
                // after the final pass when all 108k rows are done.
                threadRepository.updateLastMessageAt(threadId, mmsThread.lastMessageAt)
                threadRepository.updateLastMessagePreview(threadId, mmsThread.lastMessagePreview)
            }
            persistedThreadIds += threadId
        }
        messageRepository.insertAll(pending)
        pending.clear()
    }

    // Returns a human-readable ETA string based on elapsed time and remaining rows.
    // Extracted as a companion-object function so it can be unit-tested without a WorkManager context.
    private fun computeEta(elapsedMs: Long, done: Int, total: Int) =
        Companion.computeEta(elapsedMs, done, total)

    // Reads all parts of an MMS message and returns the text body plus stable
    // content://mms/part/{partId} URIs for every media attachment (image, video, audio).
    // Delegates the parsing rules to the shared parseMmsRawParts() — the same pure
    // function used by SmsSyncHandler.getMmsBodyIncremental().
    // Returns MmsParsedResult("[MMS]", emptyList()) when the cursor is unavailable.
    private fun getMmsBody(mmsId: Long): MmsParsedResult {
        val cursor = applicationContext.contentResolver.query(
            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$mmsId/part"),
            arrayOf("_id", Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT), null, null, null
        ) ?: run {
            Log.w(TAG_MMS, "getMmsBody: parts cursor null for mmsId=$mmsId")
            return MmsParsedResult("[MMS]", emptyList())
        }
        debugMmsLog("getMmsBody: mmsId=$mmsId  partCount=${cursor.count}")
        val rawParts = mutableListOf<MmsRawPart>()
        cursor.use {
            val idIdx   = it.getColumnIndexOrThrow("_id")
            val ctIdx   = it.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
            val textIdx = it.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)
            while (it.moveToNext()) {
                val ct = it.getString(ctIdx) ?: continue
                rawParts += MmsRawPart(it.getLong(idIdx), ct, it.getString(textIdx))
            }
        }
        return parseMmsRawParts(rawParts)
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

    // Returns every distinct participant address (FROM/TO/CC) for a group MMS PDU.
    // Mirrors SmsSyncHandler.getMmsParticipants() — see that copy for the rationale on
    // why the two sync paths don't share a single content-resolver query here.
    private fun getMmsParticipants(mmsId: Long): List<String> {
        val cursor = applicationContext.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            null, null, null
        ) ?: return emptyList()
        val rows = cursor.use {
            val addrIdx = it.getColumnIndexOrThrow("address")
            val typeIdx = it.getColumnIndexOrThrow("type")
            val out = mutableListOf<MmsAddrRow>()
            while (it.moveToNext()) {
                out += MmsAddrRow(it.getString(addrIdx), it.getInt(typeIdx))
            }
            out
        }
        return parseMmsParticipants(rows)
    }

    private fun writeStatus(status: String) {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATUS, status).apply()
    }

    private data class SyncResult(val threadCount: Int, val messageCount: Int)

    // Return value for processMmsCursor: inserted row count plus the minimum rawId seen
    // (used by syncAllMms to set the phase-2 boundary after the fast phase-1 seed).
    private data class CursorResult(val inserted: Int, val minRawId: Long)

    companion object {
        const val WORK_NAME  = "first_launch_sms_sync"
        const val KEY_STATUS = "last_sync_status"
        const val KEY_ERROR  = "last_sync_error"
        // WorkManager progress keys — readable via WorkInfo.progress while the worker is RUNNING.
        const val KEY_PROGRESS_PHASE = "progress_phase"
        const val KEY_PROGRESS_DONE  = "progress_done"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_ETA   = "progress_eta"

        /** Returns a human-readable ETA string from elapsed time and row counts.
         *  Pure function — no side effects, no clock access. Testable in isolation. */
        internal fun computeEta(elapsedMs: Long, done: Int, total: Int): String {
            if (done <= 0 || total <= done) return ""
            val remainingMs = (total - done).toLong() * elapsedMs / done
            val secs = remainingMs / 1000
            val mins = secs / 60
            val remSecs = secs % 60
            return if (mins > 0) "~${mins}m ${remSecs}s" else "~${remSecs}s"
        }
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
            OneTimeWorkRequestBuilder<SmsHistoryImportWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
    }
}
