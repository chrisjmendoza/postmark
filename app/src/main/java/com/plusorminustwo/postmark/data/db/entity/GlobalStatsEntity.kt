package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table (id always 1) holding aggregated statistics across ALL threads.
 * Computed by [StatsUpdater.recomputeAll] and read by the Stats screen global view.
 */
@Entity(tableName = "global_stats")
data class GlobalStatsEntity(
    @PrimaryKey val id: Int = 1,
    val totalMessages: Int = 0,
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    val threadCount: Int = 0,
    val activeDayCount: Int = 0,
    val longestStreakDays: Int = 0,
    val avgResponseTimeMs: Long = 0L,
    /** JSONArray of {"emoji":"😀","count":5}, top 6, sorted descending. */
    val topEmojisJson: String = "[]",
    /** JSONObject {"0":5,"1":3,...} where 0 = Monday, 6 = Sunday. */
    val byDayOfWeekJson: String = "{}",
    /** JSONObject {"0":10,"2":5,...} where 0 = January, 11 = December. */
    val byMonthJson: String = "{}",
    val lastUpdatedAt: Long = 0L
)
