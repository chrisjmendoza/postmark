package com.plusorminustwo.postmark.data.db.dao

import androidx.room.*
import com.plusorminustwo.postmark.data.db.entity.ThreadStatsEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `thread_stats` table.
 *
 * One row per thread, inserted or replaced by [StatsUpdater] after each sync.
 * Stats are read by [StatsViewModel] for display on the Stats screen.
 */
@Dao
interface ThreadStatsDao {

    @Query("SELECT * FROM thread_stats WHERE threadId = :threadId")
    fun observeByThread(threadId: Long): Flow<ThreadStatsEntity?>

    @Query("SELECT * FROM thread_stats WHERE threadId = :threadId")
    suspend fun getByThread(threadId: Long): ThreadStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: ThreadStatsEntity)

    @Update
    suspend fun update(stats: ThreadStatsEntity)

    @Query("SELECT * FROM thread_stats ORDER BY totalMessages DESC")
    fun observeAll(): Flow<List<ThreadStatsEntity>>

    @Query("""
        SELECT
            COALESCE(SUM(totalMessages), 0) as totalMessages,
            COALESCE(SUM(sentCount), 0) as sentCount,
            COALESCE(SUM(receivedCount), 0) as receivedCount
        FROM thread_stats
    """)
    suspend fun getGlobalCounts(): GlobalCounts?
}

/** Aggregate message counts across all threads, used for the global stats display. */
data class GlobalCounts(
    val totalMessages: Int,
    val sentCount: Int,
    val receivedCount: Int
)
