package com.plusorminustwo.postmark.data.db.dao

import androidx.room.*
import com.plusorminustwo.postmark.data.db.entity.GlobalStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GlobalStatsDao {

    @Query("SELECT * FROM global_stats WHERE id = 1")
    fun observe(): Flow<GlobalStatsEntity?>

    @Query("SELECT * FROM global_stats WHERE id = 1")
    suspend fun get(): GlobalStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: GlobalStatsEntity)
}
