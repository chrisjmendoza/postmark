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

    private suspend fun syncLatestSms(uri: Uri) {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            null, null,
            "${Telephony.Sms.DATE} DESC LIMIT 1"
        ) ?: return

        cursor.use {
            if (!it.moveToFirst()) return
            val id = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID))
            val threadId = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
            val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: return
            val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
            val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
            val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))
            val isSent = type == Telephony.Sms.MESSAGE_TYPE_SENT

            ensureThread(threadId, address, date)

            val existing = messageRepository.getById(id)
            if (existing != null) return

            val message = Message(id, threadId, address, body, date, isSent, type,
                // New messages from sync are always new (we returned early above if the id
                // already existed). Incoming messages (isSent=false) start as unread.
                isRead = isSent)
            messageRepository.insert(message)
            // Remove any optimistic sent message that this real message replaces
            messageRepository.deleteOptimisticMessages(threadId)
            threadRepository.updateLastMessageAt(threadId, date)
            threadRepository.updateLastMessagePreview(threadId, body)
            statsUpdater.recomputeAll()

            // Check if this is an Apple reaction fallback
            val parsed = reactionParser.parse(body)
            if (parsed != null && !parsed.isRemoval) {
                val threadMessages = messageRepository.getByThread(threadId)
                val reaction = reactionParser.processIncomingMessage(message, threadMessages, address)
                if (reaction != null) messageRepository.insertReaction(reaction)
            }
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
