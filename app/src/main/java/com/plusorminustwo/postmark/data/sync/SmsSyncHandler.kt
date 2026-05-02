package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
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

    fun onSmsContentChanged(uri: Uri) {
        scope.launch { syncLatestSms(uri) }
    }

    fun onMmsContentChanged(uri: Uri) {
        scope.launch { syncLatestMms(uri) }
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
                val address  = it.getString(addressIdx) ?: continue
                val body     = it.getString(bodyIdx) ?: ""
                val date     = it.getLong(dateIdx)
                val type     = it.getInt(typeIdx)
                val isSent   = type == Telephony.Sms.MESSAGE_TYPE_SENT

                if (threadId !in ensuredThreadIds) {
                    ensureThread(threadId, address, date)
                    ensuredThreadIds += threadId
                }
                newMessages += Message(id, threadId, address, body, date, isSent, type)
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
            threadRepository.updateLastMessagePreview(threadId, latest.body)
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
        // If nothing in DB yet, full sync hasn't run — bail out.
        if (maxStoredId <= 0L) return
        val maxRawId = maxStoredId - MMS_ID_OFFSET

        val cursor = context.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "thread_id", "date", "msg_box"),
            "_id > ?", arrayOf(maxRawId.toString()),
            "_id ASC"
        ) ?: return

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
                val isSent    = msgBox == android.provider.Telephony.Mms.MESSAGE_BOX_SENT
                val timestamp = dateSec * 1000L
                val body      = getMmsBodyIncremental(rawId)
                val address   = getMmsAddressIncremental(rawId, isSent)

                if (threadId !in ensuredThreadIds) {
                    ensureThread(threadId, address, timestamp)
                    ensuredThreadIds += threadId
                }

                newMessages += Message(
                    id = id,
                    threadId = threadId,
                    address = address,
                    body = body,
                    timestamp = timestamp,
                    isSent = isSent,
                    type = msgBox,
                    isMms = true
                )
            }
        }

        if (newMessages.isEmpty()) return

        messageRepository.insertAll(newMessages)

        newMessages.groupBy { it.threadId }.forEach { (threadId, msgs) ->
            val latest = msgs.last()
            messageRepository.deleteOptimisticMessages(threadId)
            threadRepository.updateLastMessageAt(threadId, latest.timestamp)
            threadRepository.updateLastMessagePreview(threadId, latest.body)
        }

        statsUpdater.recomputeAll()
    }

    private fun getMmsBodyIncremental(mmsId: Long): String {
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/part"),
            arrayOf("ct", "text"), null, null, null
        ) ?: return "[MMS]"
        val sb = StringBuilder()
        cursor.use {
            val ctIdx   = it.getColumnIndexOrThrow("ct")
            val textIdx = it.getColumnIndexOrThrow("text")
            while (it.moveToNext()) {
                if (it.getString(ctIdx) == "text/plain") sb.append(it.getString(textIdx) ?: "")
            }
        }
        return if (sb.isNotEmpty()) sb.toString().trim() else "[MMS]"
    }

    private fun getMmsAddressIncremental(mmsId: Long, isSent: Boolean): String {
        val addrType = if (isSent) 151 else 137
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            "type = ?", arrayOf(addrType.toString()), null
        ) ?: return "Unknown"
        return cursor.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow("address")) ?: "Unknown"
            else "Unknown"
        }
    }

    private suspend fun ensureThread(threadId: Long, address: String, timestamp: Long) {
        if (threadRepository.getById(threadId) != null) return
        val displayName = lookupContactName(address) ?: address
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
}
