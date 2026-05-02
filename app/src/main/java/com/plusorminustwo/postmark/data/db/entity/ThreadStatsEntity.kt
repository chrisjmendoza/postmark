package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Room entity holding pre-computed statistics for a single conversation thread.
 *
 * One row per thread, keyed by [threadId]. Cascade-deleted when the parent thread is removed.
 * JSON columns store complex data structures (maps, lists) as serialised strings and are
 * deserialised by the stats view model before display.
 *
 * @property topEmojisJson         JSON array of `{"emoji":"...","count":N}` objects — top emoji
 *                                  by frequency in message bodies.
 * @property topReactionEmojisJson  Same format as above but for emoji reactions (not body text).
 * @property byDayOfWeekJson        JSON object `{"1":N,"2":N,...,"7":N}` (1=Mon, 7=Sun).
 * @property byMonthJson            JSON object `{"YYYY-MM":N,...}`.
 */
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
    val topReactionEmojisJson: String = "[]",
    val byDayOfWeekJson: String = "{}",
    val byMonthJson: String = "{}",
    val lastUpdatedAt: Long = 0L
)
