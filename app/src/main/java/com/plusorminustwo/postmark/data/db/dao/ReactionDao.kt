package com.plusorminustwo.postmark.data.db.dao

import androidx.room.*
import com.plusorminustwo.postmark.data.db.entity.ReactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {

    @Query("SELECT * FROM reactions")
    fun observeAll(): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reactions")
    suspend fun getAll(): List<ReactionEntity>

    @Query("SELECT * FROM reactions WHERE messageId = :messageId")
    fun observeByMessage(messageId: Long): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reactions WHERE messageId = :messageId")
    suspend fun getByMessage(messageId: Long): List<ReactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reaction: ReactionEntity): Long

    @Delete
    suspend fun delete(reaction: ReactionEntity)

    @Query("""
        DELETE FROM reactions
        WHERE messageId = :messageId AND senderAddress = :senderAddress AND emoji = :emoji
    """)
    suspend fun deleteByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String)

    @Query("SELECT * FROM reactions WHERE emoji = :emoji")
    suspend fun getByEmoji(emoji: String): List<ReactionEntity>

    @Query("""
        SELECT emoji, COUNT(*) as count FROM reactions
        GROUP BY emoji ORDER BY count DESC LIMIT :limit
    """)
    suspend fun getTopEmojis(limit: Int = 10): List<EmojiCount>

    @Query("""
        SELECT emoji, COUNT(*) as count FROM reactions
        WHERE senderAddress = :senderAddress
        GROUP BY emoji ORDER BY count DESC
    """)
    fun observeTopEmojisBySender(senderAddress: String): Flow<List<EmojiCount>>

    @Query("""
        SELECT * FROM reactions
        WHERE messageId IN (SELECT id FROM messages WHERE threadId = :threadId)
    """)
    fun observeByThread(threadId: Long): Flow<List<ReactionEntity>>

    @Query("""
        SELECT * FROM reactions
        WHERE messageId IN (SELECT id FROM messages WHERE threadId = :threadId)
    """)
    suspend fun getByThread(threadId: Long): List<ReactionEntity>

    @Query("DELETE FROM reactions")
    suspend fun deleteAll()
}

data class EmojiCount(val emoji: String, val count: Int)
