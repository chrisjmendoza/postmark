package com.plusorminustwo.postmark.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.plusorminustwo.postmark.data.db.entity.MessageEntity

@Dao
interface SearchDao {

    @Query("""
        SELECT m.* FROM messages m
        JOIN messages_fts ON m.id = messages_fts.rowid
        WHERE messages_fts MATCH :query
        ORDER BY m.timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchMessages(
        query: String,
        limit: Int = 50,
        offset: Int = 0
    ): List<MessageEntity>

    @Query("""
        SELECT m.* FROM messages m
        JOIN messages_fts ON m.id = messages_fts.rowid
        WHERE messages_fts MATCH :query AND m.threadId = :threadId
        ORDER BY m.timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchMessagesInThread(
        query: String,
        threadId: Long,
        limit: Int = 50,
        offset: Int = 0
    ): List<MessageEntity>

    @Query("""
        SELECT m.* FROM messages m
        JOIN messages_fts ON m.id = messages_fts.rowid
        WHERE messages_fts MATCH :query AND m.isSent = :isSent
        ORDER BY m.timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchMessagesBySent(
        query: String,
        isSent: Boolean,
        limit: Int = 50,
        offset: Int = 0
    ): List<MessageEntity>

    @Query("""
        SELECT m.* FROM messages m
        JOIN messages_fts ON m.id = messages_fts.rowid
        WHERE messages_fts MATCH :query
          AND m.timestamp BETWEEN :startMs AND :endMs
        ORDER BY m.timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchMessagesInDateRange(
        query: String,
        startMs: Long,
        endMs: Long,
        limit: Int = 50,
        offset: Int = 0
    ): List<MessageEntity>
}
