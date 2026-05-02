package com.plusorminustwo.postmark.domain.model

/**
 * Aggregated statistics for a single conversation thread.
 *
 * Computed by `StatsUpdater` after each sync and stored in [com.plusorminustwo.postmark.data.db.entity.ThreadStatsEntity].
 * The [StatsViewModel] maps the entity back to this domain model for display.
 *
 * @param threadId          The thread these stats belong to.
 * @param totalMessages     Total message count (sent + received).
 * @param sentCount         Number of messages sent by the local user.
 * @param receivedCount     Number of messages received.
 * @param firstMessageAt    Epoch millis of the oldest message in this thread.
 * @param lastMessageAt     Epoch millis of the newest message.
 * @param activeDayCount    Number of distinct calendar days with at least one message.
 * @param longestStreakDays Longest run of consecutive days that both parties exchanged messages.
 * @param avgResponseTimeMs Average time between a received message and the next sent reply, in ms.
 * @param topEmojis         Most-used emoji in message bodies, ranked by frequency.
 * @param byDayOfWeek       Message count keyed by ISO day-of-week (1 = Monday … 7 = Sunday).
 * @param byMonth           Message count keyed by "YYYY-MM" month strings.
 * @param lastUpdatedAt     Epoch millis when these stats were last recomputed.
 */
data class ThreadStats(
    val threadId: Long,
    val totalMessages: Int,
    val sentCount: Int,
    val receivedCount: Int,
    val firstMessageAt: Long,
    val lastMessageAt: Long,
    val activeDayCount: Int,
    val longestStreakDays: Int,
    val avgResponseTimeMs: Long,
    val topEmojis: List<EmojiCount>,
    val byDayOfWeek: Map<Int, Int>,
    val byMonth: Map<String, Int>,
    val lastUpdatedAt: Long
)

/** A single emoji paired with its usage count, used in [ThreadStats.topEmojis]. */
data class EmojiCount(val emoji: String, val count: Int)
