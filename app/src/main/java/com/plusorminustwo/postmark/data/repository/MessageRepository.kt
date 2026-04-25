package com.plusorminustwo.postmark.data.repository

import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.entity.toDomain
import com.plusorminustwo.postmark.data.db.entity.toEntity
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Reaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun getActiveDatesForThread(threadId: Long): List<String> =
        messageDao.getActiveDatesForThread(threadId)

    suspend fun insertReaction(reaction: Reaction): Long =
        reactionDao.insert(reaction.toEntity())

    suspend fun deleteReaction(messageId: Long, senderAddress: String, emoji: String) =
        reactionDao.deleteByMessageSenderAndEmoji(messageId, senderAddress, emoji)
}
