package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
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
    private val reactionParser: AppleReactionParser
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            syncAllSms()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun syncAllSms() {
        val threads = mutableMapOf<Long, Thread>()
        val messages = mutableListOf<Message>()

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
        ) ?: return

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
                setProgress(workDataOf("progress" to (processed * 100 / total)))
            }
        }

        // Persist in batches
        threadRepository.upsertAll(threads.values.toList())
        messages.chunked(500).forEach { chunk ->
            messageRepository.insertAll(chunk)
        }

        // Run Apple reaction parser over all messages
        val allMessages = messageRepository.getByThread(0) // placeholder — iterate per thread
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

    companion object {
        const val WORK_NAME = "first_launch_sms_sync"

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
