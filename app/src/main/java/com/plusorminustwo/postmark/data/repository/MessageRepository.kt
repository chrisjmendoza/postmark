package com.plusorminustwo.postmark.data.repository

import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.entity.toDomain
import com.plusorminustwo.postmark.data.db.entity.toEntity
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.encodeAttachmentsJson
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import com.plusorminustwo.postmark.data.db.dao.UnreadCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that assembles domain [Message] objects by combining [MessageDao] rows
 * with their associated [ReactionDao] rows.
 *
 * All reactive paths use [Flow] so callers automatically receive updated data
 * whenever messages or reactions change in the database.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val reactionDao: ReactionDao
) {
    fun observeByThread(threadId: Long): Flow<List<Message>> =
        messageDao.observeByThread(threadId).combine(
            reactionDao.observeByThread(threadId)
        ) { messages, reactions ->
            val reactionsByMessage = reactions.groupBy { it.messageId }
            messages.map { entity ->
                entity.toDomain().copy(
                    reactions = reactionsByMessage[entity.id]?.map { it.toDomain() } ?: emptyList()
                )
            }
        }

    suspend fun getByThread(threadId: Long): List<Message> =
        messageDao.getByThread(threadId).map { it.toDomain() }

    suspend fun getById(messageId: Long): Message? = messageDao.getById(messageId)?.toDomain()

    suspend fun insert(message: Message): Long = messageDao.insert(message.toEntity())

    suspend fun insertAll(messages: List<Message>) =
        messageDao.insertAll(messages.map { it.toEntity() })

    suspend fun delete(message: Message) = messageDao.delete(message.toEntity())

    suspend fun deleteByThread(threadId: Long) = messageDao.deleteByThread(threadId)

    suspend fun getByThreadAndDateRange(threadId: Long, startMs: Long, endMs: Long): List<Message> =
        messageDao.getByThreadAndDateRange(threadId, startMs, endMs).map { it.toDomain() }

    fun observeTopUserEmojis(): Flow<List<String>> =
        reactionDao.observeTopEmojisBySender(SELF_ADDRESS).map { counts -> counts.map { it.emoji } }

    suspend fun insertReaction(reaction: Reaction): Long =
        reactionDao.insert(reaction.toEntity())

    suspend fun deleteReaction(messageId: Long, senderAddress: String, emoji: String) =
        reactionDao.deleteByMessageSenderAndEmoji(messageId, senderAddress, emoji)

    suspend fun updateDeliveryStatus(messageId: Long, status: Int) =
        messageDao.updateDeliveryStatus(messageId, status)

    suspend fun updateThreadId(messageId: Long, threadId: Long) =
        messageDao.updateThreadId(messageId, threadId)

    // Marks all messages in a thread as read; called when the user opens the thread.
    suspend fun markAllRead(threadId: Long) = messageDao.markAllRead(threadId)

    /** Marks a thread unread by clearing isRead on only its latest message — enough to
     *  light the unread badge via the existing pipeline. Backs the conversations-list
     *  "Mark unread" bulk action. */
    suspend fun markLatestUnread(threadId: Long) = messageDao.markLatestUnread(threadId)

    // Emits a live Map<threadId, unreadCount> for driving unread badges.
    fun observeUnreadCounts(): Flow<Map<Long, Int>> =
        messageDao.observeUnreadCounts().map { list -> list.associate { it.threadId to it.count } }

    /** Deletes optimistic (negative-ID) rows of one transport in a thread.
     *  [isMms] scoping keeps the SMS sync path from deleting a pending MMS temp row. */
    suspend fun deleteOptimisticMessages(threadId: Long, isMms: Boolean) =
        messageDao.deleteOptimisticMessages(threadId, isMms)

    // Returns the delivery status of the most recent temp sent row of a transport in a thread.
    // Used by syncLatestMms() to carry a FAILED/SENT status over to the real row.
    suspend fun getOptimisticSentDeliveryStatus(threadId: Long, isMms: Boolean): Int? =
        messageDao.getOptimisticSentDeliveryStatus(threadId, isMms)

    /** Returns the negative tempId of the most recent temp sent row of a transport in a thread.
     *  Used by [SmsSyncHandler] to derive the cache file names and bypass the race
     *  where [ThreadViewModel] hasn't yet updated the stored attachments. */
    suspend fun getOptimisticSentId(threadId: Long, isMms: Boolean): Long? =
        messageDao.getOptimisticSentId(threadId, isMms)

    /** All optimistic sent-MMS temp rows in a thread (newest first) as domain [Message]s,
     *  for per-row body/time matching in [SmsSyncHandler.syncLatestMms]. */
    suspend fun getOptimisticSentMms(threadId: Long): List<Message> =
        messageDao.getOptimisticSentMms(threadId).map { it.toDomain() }

    /** Replaces a message's attachment list, keeping the singular first-attachment
     *  columns in sync (they back observeMediaMessages and pre-v12 fallback). */
    suspend fun updateAttachments(messageId: Long, attachments: List<MessageAttachment>) =
        messageDao.updateAttachments(
            messageId,
            encodeAttachmentsJson(attachments),
            attachments.firstOrNull()?.uri,
            attachments.firstOrNull()?.mimeType
        )

    /** Deletes a single message by id (used to remove reaction fallback messages after parsing). */
    suspend fun deleteById(messageId: Long) = messageDao.deleteById(messageId)

    /** Deletes several messages by id in one statement (used by the one-shot non-displayable
     *  MMS cleanup — Room-side only, never touches the telephony provider). */
    suspend fun deleteByIds(ids: List<Long>) = messageDao.deleteByIds(ids)

    /** Returns the latest message in a thread (used to refresh thread preview after reaction cleanup). */
    suspend fun getLatestForThread(threadId: Long): Message? =
        messageDao.getLatestForThread(threadId)?.toDomain()

    /** Returns all messages ordered by timestamp (used by reprocessReactions debug tool). */
    suspend fun getAll(): List<Message> = messageDao.getAll().map { it.toDomain() }

    /** Returns every message carrying a media attachment; used by the orphaned MMS-cache sweep. */
    suspend fun getMessagesWithAttachments(): List<Message> =
        messageDao.getAllWithAttachments().map { it.toDomain() }

    /** Returns all distinct thread IDs in the messages table; used for batched iteration. */
    suspend fun getAllThreadIds(): List<Long> = messageDao.getAllThreadIds()

    /** Returns true if a reaction with the same messageId + senderAddress + emoji already exists. */
    suspend fun reactionExists(messageId: Long, senderAddress: String, emoji: String): Boolean =
        reactionDao.countByMessageSenderAndEmoji(messageId, senderAddress, emoji) > 0

    /** Returns the highest SMS provider _id stored locally (SMS rows only), or null. */
    suspend fun getMaxId(): Long? = messageDao.getMaxId()

    /** Returns the highest stored MMS row id (offset by MMS_ID_OFFSET), or null. */
    suspend fun getMaxMmsId(): Long? = messageDao.getMaxMmsId()

    /** Returns the lowest stored MMS row id (offset by MMS_ID_OFFSET), or null. */
    suspend fun getMinMmsId(): Long? = messageDao.getMinMmsId()

    /** True when any message row exists at all (restored rows included). */
    suspend fun hasAnyMessages(): Boolean = messageDao.hasAnyMessages()

    /** Highest id in the restored-row range, or null when nothing was ever restored. */
    suspend fun getMaxRestoredId(): Long? = messageDao.getMaxRestoredId()

    /** All reactions on messages of one thread — the backup writer's source
     *  (the non-reactive [getByThread] deliberately doesn't join reactions). */
    suspend fun getReactionsByThread(threadId: Long): List<Reaction> =
        reactionDao.getByThread(threadId).map { it.toDomain() }

    suspend fun deleteAll() {
        reactionDao.deleteAll()
        messageDao.deleteAll()
    }

    /** Live list of media-bearing messages in a thread, newest first.
     *  Used by ContactDetailScreen to populate the shared-media grid. */
    fun observeMediaMessages(threadId: Long): Flow<List<Message>> =
        messageDao.observeMediaMessages(threadId).map { list -> list.map { it.toDomain() } }

    suspend fun updateStarred(messageId: Long, isStarred: Boolean) =
        messageDao.updateStarred(messageId, isStarred)

    /** Live list of starred messages that carry at least one image attachment, newest
     *  first — backs the global Starred Images gallery. Filters to images specifically
     *  (not video/audio) since that's what the gallery displays; a starred message with
     *  e.g. only a video attachment simply doesn't appear here. */
    fun observeStarredImages(): Flow<List<Message>> =
        messageDao.observeStarredMedia().map { list ->
            list.map { it.toDomain() }
                .filter { msg -> msg.attachments.any { it.mimeType.startsWith("image/", ignoreCase = true) } }
        }
}
