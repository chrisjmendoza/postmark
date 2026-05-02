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
    private val statsUpdater: StatsUpdater
) : CoroutineWorker(context, params) {

    // Verbose debug logs only appear in debug builds; warnings and errors always fire.
    private fun debugLog(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, msg) }

    override suspend fun doWork(): Result {
        debugLog("doWork() started — attempt ${runAttemptCount + 1}")
        return try {
            val result = syncAllSms()
            if (result.threadCount < 0) {
                val msg = "SMS cursor was null — provider unavailable or permission denied"
                Log.w(TAG, msg)
                writeStatus("Failed: $msg")
                return if (runAttemptCount < 3) Result.retry()
                else Result.failure(workDataOf(KEY_ERROR to msg))
            }
            val status = "OK: ${result.threadCount} threads, ${result.messageCount} messages"
            Log.i(TAG, "Sync complete — $status")
            writeStatus(status)
            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("first_sync_completed", true).apply()
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
            val address  = cursor.getString(addressIdx) ?: continue
            val body     = cursor.getString(bodyIdx) ?: ""
            val date     = cursor.getLong(dateIdx)
            val type     = cursor.getInt(typeIdx)
            val isSent   = type == Telephony.Sms.MESSAGE_TYPE_SENT

            if (!threads.containsKey(threadId)) {
                val displayName = lookupContactName(address) ?: address
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

    private fun writeStatus(status: String) {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATUS, status).apply()
    }

    private data class SyncResult(val threadCount: Int, val messageCount: Int)

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
