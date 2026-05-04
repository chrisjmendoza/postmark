package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.search.parser.AndroidReactionParser
import com.plusorminustwo.postmark.search.parser.AppleReactionParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FirstLaunchSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val appleReactionParser: AppleReactionParser,
    private val androidReactionParser: AndroidReactionParser,
    private val statsUpdater: StatsUpdater
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork() started — attempt ${runAttemptCount + 1}")
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
        Log.d(TAG, "Querying content://sms …")
        val cursor = applicationContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            null, null,
            "${Telephony.Sms.DATE} ASC"
        )

        if (cursor == null) {
            Log.w(TAG, "content://sms query returned null cursor")
            return SyncResult(-1, -1)
        }

        Log.d(TAG, "Cursor opened — ${cursor.count} rows")
        val threads = mutableMapOf<Long, Thread>()
        val messages = mutableListOf<Message>()

        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdx = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val total = it.count
            var processed = 0

            while (it.moveToNext()) {
                val id = it.getLong(idIdx)
                val threadId = it.getLong(threadIdx)
                val address = it.getString(addressIdx) ?: continue
                val body = it.getString(bodyIdx) ?: ""
                val date = it.getLong(dateIdx)
                val type = it.getInt(typeIdx)
                val isSent = type == Telephony.Sms.MESSAGE_TYPE_SENT

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
                        threads[threadId] = existing.copy(
                            lastMessageAt = date,
                            lastMessagePreview = body
                        )
                    }
                }

                messages.add(Message(id, threadId, address, body, date, isSent, type))
                processed++
                if (total > 0) setProgress(workDataOf("progress" to (processed * 100 / total)))
            }
        }

        Log.d(TAG, "Persisting ${threads.size} threads, ${messages.size} messages …")
        threadRepository.upsertAll(threads.values.toList())
        messages.chunked(500).forEach { chunk -> messageRepository.insertAll(chunk) }

        Log.d(TAG, "Running reaction parser …")
        threads.keys.forEach { threadId ->
            val threadMsgs = messageRepository.getByThread(threadId)
            threadMsgs.forEach { msg ->
                val appleParsed = appleReactionParser.parse(msg.body)
                val androidParsed = if (appleParsed == null) androidReactionParser.parse(msg.body) else null
                val reaction = when {
                    appleParsed != null && !appleParsed.isRemoval ->
                        appleReactionParser.processIncomingMessage(msg, threadMsgs, msg.address)
                    androidParsed != null && !androidParsed.isRemoval ->
                        androidReactionParser.processIncomingMessage(msg, threadMsgs, msg.address)
                    else -> null
                }
                if (reaction != null) messageRepository.insertReaction(reaction)
            }
        }

        Log.d(TAG, "Computing thread stats …")
        statsUpdater.recomputeAll()

        return SyncResult(threads.size, messages.size)
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
