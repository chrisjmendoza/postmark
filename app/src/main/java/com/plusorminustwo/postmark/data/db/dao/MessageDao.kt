package com.plusorminustwo.postmark.data.db.dao

import androidx.room.*
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp ASC")
    fun observeByThread(threadId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp ASC")
    suspend fun getByThread(threadId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE threadId = :threadId")
    suspend fun countByThread(threadId: Long): Int

    @Query("""
        SELECT * FROM messages
        WHERE threadId = :threadId AND timestamp BETWEEN :startMs AND :endMs
        ORDER BY timestamp ASC
    """)
    suspend fun getByThreadAndDateRange(threadId: Long, startMs: Long, endMs: Long): List<MessageEntity>

    @Query("""
        SELECT DISTINCT date(timestamp / 1000, 'unixepoch')
        FROM messages WHERE threadId = :threadId
    """)
    suspend fun getActiveDatesForThread(threadId: Long): List<String>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForThread(threadId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC LIMIT :n")
    suspend fun getLatestNForThread(threadId: Long, n: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE threadId = :threadId AND timestamp < :timestamp ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestBeforeForThread(threadId: Long, timestamp: Long): MessageEntity?

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :messageId")
    suspend fun updateDeliveryStatus(messageId: Long, status: Int)

    @Query("DELETE FROM messages WHERE threadId = :threadId AND id < 0")
    suspend fun deleteOptimisticMessages(threadId: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
