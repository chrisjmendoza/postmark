package com.plusorminustwo.postmark.data.db.dao

import androidx.room.*
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** Projection returned by [MessageDao.observeUnreadCounts]. */
data class UnreadCount(val threadId: Long, val count: Int)

/**
 * DAO for the `messages` table.
 *
 * Provides queries for reading and writing [MessageEntity] rows. All observable queries
 * return [Flow] so Room automatically re-emits when the underlying data changes.
 *
 * ID conventions:
 *  - Positive IDs ≥ 1 are real content-provider rows (SMS or MMS offset).
 *  - Negative IDs are optimistic rows inserted before telephony confirms the send.
 *  - MMS rows use `id = rawMmsId + MMS_ID_OFFSET` to avoid collisions with SMS IDs.
 */
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

    @Query("UPDATE messages SET attachmentUri = :uri WHERE id = :messageId")
    suspend fun updateAttachmentUri(messageId: Long, uri: String)

    @Query("DELETE FROM messages WHERE threadId = :threadId AND id < 0")
    suspend fun deleteOptimisticMessages(threadId: Long)

    /** Returns the [MessageEntity.deliveryStatus] of the most recent optimistic (negative-ID)
     *  sent message in a thread. Used by [SmsSyncHandler.syncLatestMms] to transfer a FAILED
     *  status to the real row before the temp row is deleted. */
    @Query("SELECT deliveryStatus FROM messages WHERE threadId = :threadId AND id < 0 AND isSent = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getOptimisticSentDeliveryStatus(threadId: Long): Int?

    /** Returns the attachmentUri of the most recent optimistic sent message in a thread.
     *  Used by [SmsSyncHandler.syncLatestMms] to transfer the locally-cached image URI
     *  to the real row, since Samsung's content://mms/part/ data may be empty for sent rows. */
    @Query("SELECT attachmentUri FROM messages WHERE threadId = :threadId AND id < 0 AND isSent = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getOptimisticSentAttachmentUri(threadId: Long): String?

    /** Returns the row id (negative tempId) of the most recent optimistic sent message in a thread.
     *  Used by [SmsSyncHandler.syncLatestMms] to derive the cache file name
     *  (mms_attach_<tempId>.bin) and build a stable FileProvider URI, bypassing the
     *  race where [ThreadViewModel] hasn't yet updated the stored attachmentUri. */
    @Query("SELECT id FROM messages WHERE threadId = :threadId AND id < 0 AND isSent = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getOptimisticSentId(threadId: Long): Long?

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestNonReactionForThread(threadId: Long): MessageEntity?

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT threadId FROM messages")
    suspend fun getAllThreadIds(): List<Long>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<MessageEntity>

    /** Marks every message in the given thread as read; called when the user opens the thread. */
    @Query("UPDATE messages SET isRead = 1 WHERE threadId = :threadId")
    suspend fun markAllRead(threadId: Long)

    /** All messages in a thread that carry a media attachment, newest first.
     *  Used by ContactDetailScreen to build the shared-media grid. */
    @Query("SELECT * FROM messages WHERE threadId = :threadId AND attachmentUri IS NOT NULL ORDER BY timestamp DESC")
    fun observeMediaMessages(threadId: Long): Flow<List<MessageEntity>>

    /** Live (threadId → unread count) pairs used by [ConversationsViewModel] for unread badges. */
    @Query("SELECT threadId, COUNT(*) as count FROM messages WHERE isRead = 0 GROUP BY threadId")
    fun observeUnreadCounts(): Flow<List<UnreadCount>>

    /** Highest SMS provider _id stored in Room (SMS only, excluding MMS offset rows).
     *  Used by [SmsSyncHandler] to bound incremental queries to rows not yet imported. */
    @Query("SELECT MAX(id) FROM messages WHERE isMms = 0")
    suspend fun getMaxId(): Long?

    /** Highest stored MMS row id (already offset by MMS_ID_OFFSET).
     *  Subtract MMS_ID_OFFSET to get the raw content-provider `_id`.
     *  Used by [SmsSyncHandler] to bound incremental MMS queries. */
    @Query("SELECT MAX(id) FROM messages WHERE isMms = 1")
    suspend fun getMaxMmsId(): Long?

    /** Lowest stored MMS row id (already offset by MMS_ID_OFFSET).
     *  Subtract MMS_ID_OFFSET to get the raw content-provider `_id`.
     *  Used by [SmsHistoryImportWorker] to resume a newest-first MMS import. */
    @Query("SELECT MIN(id) FROM messages WHERE isMms = 1")
    suspend fun getMinMmsId(): Long?

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
