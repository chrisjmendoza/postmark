package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "thread_stats",
    foreignKeys = [
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ThreadStatsEntity(
    @PrimaryKey val threadId: Long,
    val totalMessages: Int = 0,
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    val firstMessageAt: Long = 0L,
    val lastMessageAt: Long = 0L,
    val activeDayCount: Int = 0,
    val longestStreakDays: Int = 0,
    val avgResponseTimeMs: Long = 0L,
    val topEmojisJson: String = "[]",
    val byDayOfWeekJson: String = "{}",
    val byMonthJson: String = "{}",
    val lastUpdatedAt: Long = 0L
)
