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
     *  sent message of the given transport in a thread. [isMms] scoping ensures a newer
     *  optimistic SMS row can't shadow the MMS row.
     *  Note: [SmsSyncHandler.syncLatestMms] no longer uses this latest-to-latest read for MMS —
     *  it matches each real row to a specific optimistic row via [getOptimisticSentMms]
     *  (see pickOptimisticMatch). Retained as a scoped optimistic-row read (DAO-test-covered). */
    @Query("SELECT deliveryStatus FROM messages WHERE threadId = :threadId AND id < 0 AND isSent = 1 AND isMms = :isMms ORDER BY id DESC LIMIT 1")
    suspend fun getOptimisticSentDeliveryStatus(threadId: Long, isMms: Boolean): Int?

    /** Returns the row id (negative tempId) of the most recent optimistic sent message of the
     *  given transport in a thread. [isMms] scoping ensures a newer optimistic SMS row can't
     *  shadow the MMS row.
     *  Note: [SmsSyncHandler.syncLatestMms] no longer uses this latest-to-latest read for MMS —
     *  it matches each real row to a specific optimistic row via [getOptimisticSentMms]
     *  (see pickOptimisticMatch). Retained as a scoped optimistic-row read (DAO-test-covered). */
    @Query("SELECT id FROM messages WHERE threadId = :threadId AND id < 0 AND isSent = 1 AND isMms = :isMms ORDER BY id DESC LIMIT 1")
    suspend fun getOptimisticSentId(threadId: Long, isMms: Boolean): Long?

    /** All optimistic (negative-ID) sent MMS temp rows in a thread, newest (largest id) first.
     *  [SmsSyncHandler.syncLatestMms] matches each just-imported real sent row to at most one
     *  of these per-row by trimmed body + send time (see pickOptimisticMatch), replacing the
     *  old LIMIT 1 latest-real-to-latest-optimistic heuristic that could graft one send's
     *  attachments onto an unrelated row and then blanket-delete the correctly-timed one. */
    @Query("SELECT * FROM messages WHERE threadId = :threadId AND id < 0 AND isSent = 1 AND isMms = 1 ORDER BY id DESC")
    suspend fun getOptimisticSentMms(threadId: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)

    /** Deletes several rows by id in one statement. Used by the one-shot non-displayable
     *  MMS cleanup (GROUP_MESSAGING_SPEC §4.2) — Room-side only, never touches the
     *  telephony provider. */
    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Latest message in a thread by timestamp — used to refresh the thread preview after
     *  reaction cleanup. No reaction filtering (the former name `getLatestNonReactionForThread`
     *  falsely implied it). */
    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForThread(threadId: Long): MessageEntity?

    /** Provider-backed MMS rows that imported with no readable content (empty body, no
     *  attachment) — candidates for the part re-read repair pass (EmptyMmsBodyRepair).
     *  Google Messages' RCS archival stores text parts file-backed and can be observed
     *  mid-persist, so a row can import empty and — the incremental watermark only moves
     *  forward — stay empty forever without this. Newest first so fresh races are healed
     *  before historical leftovers; id bounds exclude optimistic (< 0) and restored
     *  (>= RESTORED_ID_OFFSET) rows, which have no provider row to re-read. */
    @Query("""
        SELECT * FROM messages
        WHERE isMms = 1 AND body = '' AND attachmentUri IS NULL AND attachmentsJson IS NULL
          AND id > 0 AND id < 20000000000
        ORDER BY timestamp DESC LIMIT :limit
    """)
    suspend fun getEmptyMmsRows(limit: Int): List<MessageEntity>

    /** Replaces a message's body text. Used by EmptyMmsBodyRepair after a successful
     *  part re-read; the FTS sync triggers keep the search index in step. */
    @Query("UPDATE messages SET body = :body WHERE id = :messageId")
    suspend fun updateBody(messageId: Long, body: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT threadId FROM messages")
    suspend fun getAllThreadIds(): List<Long>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<MessageEntity>

    /** Marks every message in the given thread as read; called when the user opens the thread.
     *  The `AND isRead = 0` predicate matters: SQLite fires UPDATE triggers (including the
     *  FTS sync trigger) and Room invalidation for every matched row even when the value is
     *  unchanged, so without it re-opening an already-read 10k-message thread rewrote 10k
     *  FTS entries and re-ran every messages observer during the screen-entry animation. */
    @Query("UPDATE messages SET isRead = 1 WHERE threadId = :threadId AND isRead = 0")
    suspend fun markAllRead(threadId: Long)

    /** Marks a thread unread by clearing isRead on its single latest message only.
     *  Reuses the whole existing unread-badge pipeline ([observeUnreadCounts]) with no
     *  schema change: one unread row is enough to surface the badge and the "Unread"
     *  filter, and marking exactly one row keeps the reverse of [markAllRead] cheap
     *  (no bulk FTS-trigger rewrite). Called by the conversations-list "Mark unread"
     *  bulk action, per selected thread. */
    @Query("UPDATE messages SET isRead = 0 WHERE id = (SELECT id FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC LIMIT 1)")
    suspend fun markLatestUnread(threadId: Long)

    /** All messages in a thread that carry a media attachment, newest first.
     *  Used by ContactDetailScreen to build the shared-media grid. */
    @Query("SELECT * FROM messages WHERE threadId = :threadId AND attachmentUri IS NOT NULL ORDER BY timestamp DESC")
    fun observeMediaMessages(threadId: Long): Flow<List<MessageEntity>>

    /** Every message (any thread) carrying a media attachment. Backs the orphaned
     *  outgoing-MMS cache sweep, which needs the full set of still-referenced cache URIs. */
    @Query("SELECT * FROM messages WHERE attachmentUri IS NOT NULL")
    suspend fun getAllWithAttachments(): List<MessageEntity>

    /** Live (threadId → unread count) pairs used by [ConversationsViewModel] for unread badges. */
    @Query("SELECT threadId, COUNT(*) as count FROM messages WHERE isRead = 0 GROUP BY threadId")
    fun observeUnreadCounts(): Flow<List<UnreadCount>>

    /** Highest SMS provider _id stored in Room (SMS only, excluding MMS offset rows).
     *  Used by [SmsSyncHandler] to bound incremental queries to rows not yet imported.
     *  The `id < 20000000000` (RESTORED_ID_OFFSET) guard excludes rows inserted by
     *  backup restore — those have no provider row, and letting one become the
     *  watermark would make every future real message silently unsynced. */
    @Query("SELECT MAX(id) FROM messages WHERE isMms = 0 AND id < 20000000000")
    suspend fun getMaxId(): Long?

    /** Highest stored MMS row id (already offset by MMS_ID_OFFSET).
     *  Subtract MMS_ID_OFFSET to get the raw content-provider `_id`.
     *  Used by [SmsSyncHandler] to bound incremental MMS queries.
     *  Excludes restored rows (id >= RESTORED_ID_OFFSET) for the same watermark
     *  reason as [getMaxId]. */
    @Query("SELECT MAX(id) FROM messages WHERE isMms = 1 AND id < 20000000000")
    suspend fun getMaxMmsId(): Long?

    /** Lowest stored MMS row id (already offset by MMS_ID_OFFSET).
     *  Subtract MMS_ID_OFFSET to get the raw content-provider `_id`.
     *  Used by [SmsHistoryImportWorker] to resume a newest-first MMS import.
     *  The `id > 0` guard excludes optimistic sent-MMS rows (which have negative IDs
     *  like `-System.currentTimeMillis()`). Without it, a live optimistic row would make
     *  resumeBeforeRawId go deeply negative, causing `rawId >= resumeBeforeRawId` to be
     *  true for every positive rawId and silently skipping the entire MMS import.
     *  The `id < 20000000000` guard excludes restored rows, which aren't provider rows
     *  and must not influence the resume watermark. */
    @Query("SELECT MIN(id) FROM messages WHERE isMms = 1 AND id > 0 AND id < 20000000000")
    suspend fun getMinMmsId(): Long?

    /** True when any message row exists, including restored rows. Used by
     *  [ConversationsViewModel]'s recovery check — unlike the watermark queries above,
     *  restored rows COUNT as messages here, otherwise a device holding only restored
     *  history would re-trigger the full provider import on every launch. */
    @Query("SELECT EXISTS(SELECT 1 FROM messages)")
    suspend fun hasAnyMessages(): Boolean

    /** Highest id in the restored-row range (RESTORED_ID_OFFSET and above), or null.
     *  Seeds the id sequence of a new restore so it can't collide with rows an
     *  earlier (possibly interrupted) restore already inserted. */
    @Query("SELECT MAX(id) FROM messages WHERE id >= 20000000000")
    suspend fun getMaxRestoredId(): Long?

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

    @Query("UPDATE messages SET isStarred = :isStarred WHERE id = :messageId")
    suspend fun updateStarred(messageId: Long, isStarred: Boolean)

    @Query("UPDATE messages SET isPinned = :isPinned WHERE id = :messageId")
    suspend fun updatePinned(messageId: Long, isPinned: Boolean)

    /** This thread's pinned messages, oldest first (Discord-style) — backs the per-thread
     *  Pinned messages panel. threadId is index-covered; the small pinned subset is filtered
     *  in-row, so no dedicated isPinned index is needed. */
    @Query("SELECT * FROM messages WHERE threadId = :threadId AND isPinned = 1 ORDER BY timestamp ASC")
    fun observePinnedByThread(threadId: Long): Flow<List<MessageEntity>>

    /** Every starred message with a media attachment, newest first — backs the global
     *  Starred Images gallery (Settings → Starred images). Image-vs-video/audio filtering
     *  happens in the repository layer after decoding attachmentsJson, since a single
     *  starred message can carry a mix of attachment types. */
    @Query("SELECT * FROM messages WHERE isStarred = 1 AND attachmentUri IS NOT NULL ORDER BY timestamp DESC")
    fun observeStarredMedia(): Flow<List<MessageEntity>>
}
