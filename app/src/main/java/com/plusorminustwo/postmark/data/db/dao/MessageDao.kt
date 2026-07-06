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

    /** Moves a message to another thread. Used by MmsSentReceiver to repair a sent MMS
     *  the platform persisted with a wrong thread_id (Room ids mirror system thread ids). */
    @Query("UPDATE messages SET threadId = :threadId WHERE id = :messageId")
    suspend fun updateThreadId(messageId: Long, threadId: Long)

    /** Replaces a message's full attachment set. [attachmentsJson] is the encoded list
     *  (see encodeAttachmentsJson); [firstUri]/[firstMime] mirror the first entry so the
     *  singular columns (used by [observeMediaMessages] and pre-v12 fallback) stay in sync. */
    @Query("UPDATE messages SET attachmentsJson = :attachmentsJson, attachmentUri = :firstUri, mimeType = :firstMime WHERE id = :messageId")
    suspend fun updateAttachments(messageId: Long, attachmentsJson: String?, firstUri: String?, firstMime: String?)

    /** Deletes optimistic (negative-ID) rows of ONE transport in a thread.
     *  Scoped by [isMms] — mirroring [getMaxId]/[getMaxMmsId] — so the SMS sync path
     *  can never delete a still-pending optimistic MMS row (and vice versa). Without
     *  this scoping, sending an SMS right after an MMS made the image bubble vanish:
     *  the fast SMS sync deleted the MMS temp row before its real row had synced in. */
    @Query("DELETE FROM messages WHERE threadId = :threadId AND id < 0 AND isMms = :isMms")
    suspend fun deleteOptimisticMessages(threadId: Long, isMms: Boolean)

    /** Returns the [MessageEntity.deliveryStatus] of the most recent optimistic (negative-ID)
     *  sent message of the given transport in a thread. Used by [SmsSyncHandler.syncLatestMms]
     *  to transfer a FAILED status to the real row before the temp row is deleted.
     *  [isMms] scoping ensures a newer optimistic SMS row can't shadow the MMS row. */
    @Query("SELECT deliveryStatus FROM messages WHERE threadId = :threadId AND id < 0 AND isSent = 1 AND isMms = :isMms ORDER BY id DESC LIMIT 1")
    suspend fun getOptimisticSentDeliveryStatus(threadId: Long, isMms: Boolean): Int?

    /** Returns the row id (negative tempId) of the most recent optimistic sent message of the
     *  given transport in a thread. Used by [SmsSyncHandler.syncLatestMms] to derive the
     *  cache file names (mms_attach_<tempId>*.bin) and build stable FileProvider URIs,
     *  bypassing the race where [ThreadViewModel] hasn't yet updated the stored attachments. */
    @Query("SELECT id FROM messages WHERE threadId = :threadId AND id < 0 AND isSent = 1 AND isMms = :isMms ORDER BY id DESC LIMIT 1")
    suspend fun getOptimisticSentId(threadId: Long, isMms: Boolean): Long?

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
     *  Used by [SmsHistoryImportWorker] to resume a newest-first MMS import.
     *  The `id > 0` guard excludes optimistic sent-MMS rows (which have negative IDs
     *  like `-System.currentTimeMillis()`). Without it, a live optimistic row would make
     *  resumeBeforeRawId go deeply negative, causing `rawId >= resumeBeforeRawId` to be
     *  true for every positive rawId and silently skipping the entire MMS import. */
    @Query("SELECT MIN(id) FROM messages WHERE isMms = 1 AND id > 0")
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
