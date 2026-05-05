package com.plusorminustwo.postmark.data.db.dao

import androidx.room.*
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** Projection returned by [MessageDao.observeUnreadCounts]. */
data class UnreadCount(val threadId: Long, val count: Int)

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

    @Query("UPDATE messages SET isRead = 1 WHERE threadId = :threadId")
    suspend fun markAllRead(threadId: Long)

    @Query("SELECT threadId, COUNT(*) as count FROM messages WHERE isRead = 0 GROUP BY threadId")
    fun observeUnreadCounts(): Flow<List<UnreadCount>>

    @Query("DELETE FROM messages WHERE threadId = :threadId AND id < 0")
    suspend fun deleteOptimisticMessages(threadId: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT threadId FROM messages")
    suspend fun getAllThreadIds(): List<Long>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<MessageEntity>

    /** Used for the 8-week activity heatmap (all threads). */
    @Query("SELECT * FROM messages WHERE timestamp >= :startMs ORDER BY timestamp ASC")
    fun observeMessagesFrom(startMs: Long): Flow<List<MessageEntity>>

    /** Used for the 8-week activity heatmap (single thread). */
    @Query("SELECT * FROM messages WHERE threadId = :threadId AND timestamp >= :startMs ORDER BY timestamp ASC")
    fun observeMessagesFromForThread(threadId: Long, startMs: Long): Flow<List<MessageEntity>>

    /** Month-scoped heatmap (all threads). */
    @Query("SELECT * FROM messages WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp ASC")
    fun observeMessagesInRange(startMs: Long, endMs: Long): Flow<List<MessageEntity>>

    /** Month-scoped heatmap (single thread). */
    @Query("SELECT * FROM messages WHERE threadId = :threadId AND timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp ASC")
    fun observeMessagesInRangeForThread(threadId: Long, startMs: Long, endMs: Long): Flow<List<MessageEntity>>
}
