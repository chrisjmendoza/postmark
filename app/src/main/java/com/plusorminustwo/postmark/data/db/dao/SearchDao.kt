package com.plusorminustwo.postmark.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.plusorminustwo.postmark.data.db.entity.MessageEntity

/**
 * Use sentinel value -1 for optional Long/Int params to express "no filter":
 *  threadId = -1L  → all threads
 *  isSentInt = -1  → sent and received
 *  startMs = -1L   → no lower timestamp bound
 *  isMmsInt = -1   → SMS and MMS
 */
@Dao
interface SearchDao {

    /**
     * General-purpose FTS search with all filters optional via sentinel -1.
     */
    @Query("""
        SELECT m.* FROM messages m
        JOIN messages_fts ON m.id = messages_fts.rowid
        WHERE messages_fts MATCH :query
          AND (:threadId = -1 OR m.threadId = :threadId)
          AND (:isSentInt = -1 OR m.isSent = :isSentInt)
          AND (:startMs = -1 OR m.timestamp >= :startMs)
          AND (:isMmsInt = -1 OR m.isMms = :isMmsInt)
        ORDER BY m.timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchMessagesFiltered(
        query: String,
        threadId: Long = -1L,
        isSentInt: Int = -1,
        startMs: Long = -1L,
        isMmsInt: Int = -1,
        limit: Int = 50,
        offset: Int = 0
    ): List<MessageEntity>

    /**
     * Same as [searchMessagesFiltered] but restricted to messages that have
     * at least one reaction with the given [reactionEmoji].
     */
    @Query("""
        SELECT m.* FROM messages m
        JOIN messages_fts ON m.id = messages_fts.rowid
        WHERE messages_fts MATCH :query
          AND (:threadId = -1 OR m.threadId = :threadId)
          AND (:isSentInt = -1 OR m.isSent = :isSentInt)
          AND (:startMs = -1 OR m.timestamp >= :startMs)
          AND (:isMmsInt = -1 OR m.isMms = :isMmsInt)
          AND m.id IN (SELECT DISTINCT messageId FROM reactions WHERE emoji = :reactionEmoji)
        ORDER BY m.timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchMessagesFilteredWithReaction(
        query: String,
        threadId: Long = -1L,
        isSentInt: Int = -1,
        startMs: Long = -1L,
        isMmsInt: Int = -1,
        reactionEmoji: String,
        limit: Int = 50,
        offset: Int = 0
    ): List<MessageEntity>

    /**
     * Protocol-only browse (no FTS query required). Used when the user selects
     * SMS or MMS filter without typing any search text — lets them scroll all
     * MMS messages globally to find images/audio that made it into the DB.
     */
    @Query("""
        SELECT * FROM messages
        WHERE (:threadId = -1 OR threadId = :threadId)
          AND (:isSentInt = -1 OR isSent = :isSentInt)
          AND (:startMs = -1 OR timestamp >= :startMs)
          AND (:isMmsInt = -1 OR isMms = :isMmsInt)
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun browseFiltered(
        threadId: Long = -1L,
        isSentInt: Int = -1,
        startMs: Long = -1L,
        isMmsInt: Int = -1,
        limit: Int = 200,
        offset: Int = 0
    ): List<MessageEntity>
}
