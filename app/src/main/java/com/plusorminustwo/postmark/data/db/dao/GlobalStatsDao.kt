package com.plusorminustwo.postmark.data.db.dao

import androidx.room.*
import com.plusorminustwo.postmark.data.db.entity.GlobalStatsEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `global_stats` table.
 *
 * Single-row table (id = 1) holding aggregated stats across all threads.
 * Written by [StatsUpdater.recomputeAll] and read by [StatsViewModel].
 */
@Dao
interface GlobalStatsDao {

    @Query("SELECT * FROM global_stats WHERE id = 1")
    fun observe(): Flow<GlobalStatsEntity?>

    @Query("SELECT * FROM global_stats WHERE id = 1")
    suspend fun get(): GlobalStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: GlobalStatsEntity)
}
