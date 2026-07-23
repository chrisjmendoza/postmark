package com.plusorminustwo.postmark.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Timezone-free per-thread aggregates, one row per thread ([observeThreadAggregates]).
 * COUNT/SUM/MIN/MAX are computed inside SQLite so opening Stats never materializes the
 * full `messages` table just to add rows up.
 */
data class ThreadAggregateRow(
    val threadId: Long,
    val totalMessages: Int,
    val sentCount: Int,
    val firstMessageAt: Long,
    val lastMessageAt: Long
)

/** Timezone-free global aggregate — a single row across every thread. */
data class GlobalAggregateRow(
    val totalMessages: Int,
    val sentCount: Int,
    val firstMessageAt: Long,
    val lastMessageAt: Long
)

/**
 * Lean per-message projection. The zone-dependent day/streak/day-of-week/month buckets
 * and response-time gaps stay in Kotlin (SQLite `strftime('localtime')` day-bucketing
 * cannot be parity-proven without on-device runs — see the deleted getActiveDatesForThread
 * whose UTC bucketing silently disagreed with the UI's device-zone dates), but they now
 * observe this 3-primitive projection instead of the full [MessageEntity] — no body /
 * attachment / address strings materialized on every invalidation.
 */
data class MessageMeta(
    val threadId: Long,
    val timestamp: Long,
    val isSent: Boolean
)

/** Body projection restricted to non-empty bodies, for emoji extraction only. */
data class EmojiBodyRow(
    val threadId: Long,
    val body: String
)

/** Reaction emoji joined to its message's thread — for per-thread / global reaction counts
 *  without carrying a messageId→threadId map through the projection. */
data class ThreadEmojiRow(
    val threadId: Long,
    val emoji: String
)

/**
 * Read-only DAO backing the Stats screen.
 *
 * Registering this alongside the other DAOs on [com.plusorminustwo.postmark.data.db.PostmarkDatabase]
 * is NOT a schema change — no entity or index is added, the version stays 20, and the
 * exported schema is unchanged. All queries are value projections, never `SELECT *`.
 */
@Dao
interface StatsDao {

    /** Per-thread COUNT/SUM(isSent)/MIN/MAX, grouped in SQLite. Received = total − sent. */
    @Query("""
        SELECT threadId AS threadId,
               COUNT(*) AS totalMessages,
               COALESCE(SUM(isSent), 0) AS sentCount,
               MIN(timestamp) AS firstMessageAt,
               MAX(timestamp) AS lastMessageAt
        FROM messages
        GROUP BY threadId
    """)
    fun observeThreadAggregates(): Flow<List<ThreadAggregateRow>>

    /** Global COUNT/SUM(isSent)/MIN/MAX across every thread. COALESCE keeps the row
     *  well-formed (zeros) when the table is empty. */
    @Query("""
        SELECT COUNT(*) AS totalMessages,
               COALESCE(SUM(isSent), 0) AS sentCount,
               COALESCE(MIN(timestamp), 0) AS firstMessageAt,
               COALESCE(MAX(timestamp), 0) AS lastMessageAt
        FROM messages
    """)
    fun observeGlobalAggregates(): Flow<GlobalAggregateRow>

    /** Lean (threadId, timestamp, isSent) for every message — drives the zone-dependent
     *  Kotlin buckets (active days, streak, day-of-week, month, response time). */
    @Query("SELECT threadId AS threadId, timestamp AS timestamp, isSent AS isSent FROM messages")
    fun observeMessageMetas(): Flow<List<MessageMeta>>

    /** (threadId, body) for messages with a non-empty body — emoji extraction only. */
    @Query("SELECT threadId AS threadId, body AS body FROM messages WHERE body != ''")
    fun observeEmojiBodies(): Flow<List<EmojiBodyRow>>

    /** Reaction emoji per owning thread (INNER JOIN messages). Reactions FK-cascade to
     *  messages, so every reaction has a matching row — no orphans are dropped. */
    @Query("""
        SELECT m.threadId AS threadId, r.emoji AS emoji
        FROM reactions r
        INNER JOIN messages m ON r.messageId = m.id
    """)
    fun observeReactionEmojisByThread(): Flow<List<ThreadEmojiRow>>

    /** Month-scoped lean metas (all threads) — heatmap grid, day-of-week chart, top contacts. */
    @Query("""
        SELECT threadId AS threadId, timestamp AS timestamp, isSent AS isSent
        FROM messages
        WHERE timestamp >= :startMs AND timestamp < :endMs
    """)
    fun observeMessageMetasInRange(startMs: Long, endMs: Long): Flow<List<MessageMeta>>

    /** Month-scoped lean metas (single thread). */
    @Query("""
        SELECT threadId AS threadId, timestamp AS timestamp, isSent AS isSent
        FROM messages
        WHERE threadId = :threadId AND timestamp >= :startMs AND timestamp < :endMs
    """)
    fun observeMessageMetasInRangeForThread(threadId: Long, startMs: Long, endMs: Long): Flow<List<MessageMeta>>
}
