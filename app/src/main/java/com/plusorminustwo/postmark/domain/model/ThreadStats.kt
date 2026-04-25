package com.plusorminustwo.postmark.domain.model

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

data class EmojiCount(val emoji: String, val count: Int)
