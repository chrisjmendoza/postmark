package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ThreadStatsDao
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadStatsEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsUpdater @Inject constructor(
    private val messageDao: MessageDao,
    private val threadStatsDao: ThreadStatsDao
) {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    suspend fun computeForAllThreads(threadIds: Collection<Long>) {
        threadIds.forEach { computeForThread(it) }
    }

    suspend fun computeForThread(threadId: Long) {
        val messages = messageDao.getByThread(threadId)
        if (messages.isEmpty()) return
        threadStatsDao.upsert(buildStats(threadId, messages))
    }

    /** Incremental update: loads only the 2 most recent messages + existing stats. */
    suspend fun updateForNewMessage(threadId: Long) {
        val existing = threadStatsDao.getByThread(threadId)
        if (existing == null) {
            computeForThread(threadId)
            return
        }
        val recent = messageDao.getLatestNForThread(threadId, 2)
        if (recent.isEmpty()) return
        val newMsg = recent.first()
        threadStatsDao.upsert(mergeStats(existing, newMsg, threadId))
    }

    private suspend fun mergeStats(
        existing: ThreadStatsEntity,
        msg: MessageEntity,
        threadId: Long
    ): ThreadStatsEntity {
        val newDay = dayFormat.format(Date(msg.timestamp))
        val lastDay = if (existing.lastMessageAt > 0) dayFormat.format(Date(existing.lastMessageAt)) else ""
        val isNewDay = msg.timestamp > existing.lastMessageAt && newDay != lastDay

        var activeDayCount = existing.activeDayCount
        var longestStreakDays = existing.longestStreakDays
        if (isNewDay) {
            activeDayCount++
            longestStreakDays = computeLongestStreakDays(threadId)
        }

        var avgResponseTimeMs = existing.avgResponseTimeMs
        val prevMsg = messageDao.getLatestBeforeForThread(threadId, msg.timestamp)
        if (prevMsg != null && prevMsg.isSent != msg.isSent) {
            val responseTime = msg.timestamp - prevMsg.timestamp
            val pairs = existing.totalMessages.coerceAtLeast(1)
            avgResponseTimeMs = (existing.avgResponseTimeMs * (pairs - 1) + responseTime) / pairs
        }

        val emojiCounts = emojiJsonToMap(existing.topEmojisJson)
        extractEmojis(msg.body).forEach { emoji ->
            emojiCounts[emoji] = (emojiCounts[emoji] ?: 0) + 1
        }

        val cal = Calendar.getInstance().apply { timeInMillis = msg.timestamp }
        val dow = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        val byDow = jsonToMapIntInt(existing.byDayOfWeekJson).apply {
            this[dow] = (this[dow] ?: 0) + 1
        }
        val month = cal.get(Calendar.MONTH)
        val byMonth = jsonToMapStringInt(existing.byMonthJson).apply {
            val key = month.toString()
            this[key] = (this[key] ?: 0) + 1
        }

        return existing.copy(
            totalMessages = existing.totalMessages + 1,
            sentCount = if (msg.isSent) existing.sentCount + 1 else existing.sentCount,
            receivedCount = if (!msg.isSent) existing.receivedCount + 1 else existing.receivedCount,
            firstMessageAt = if (existing.firstMessageAt == 0L) msg.timestamp
                             else minOf(existing.firstMessageAt, msg.timestamp),
            lastMessageAt = maxOf(existing.lastMessageAt, msg.timestamp),
            activeDayCount = activeDayCount,
            longestStreakDays = longestStreakDays,
            avgResponseTimeMs = avgResponseTimeMs,
            topEmojisJson = emojiMapToJson(emojiCounts),
            byDayOfWeekJson = mapIntIntToJson(byDow),
            byMonthJson = mapStringIntToJson(byMonth),
            lastUpdatedAt = System.currentTimeMillis()
        )
    }

    private fun buildStats(threadId: Long, messages: List<MessageEntity>): ThreadStatsEntity {
        val sorted = messages.sortedBy { it.timestamp }
        val totalMessages = sorted.size
        val sentCount = sorted.count { it.isSent }

        val activeDays = sorted.map { dayFormat.format(Date(it.timestamp)) }.distinct().sorted()

        val emojiCounts = mutableMapOf<String, Int>()
        val cal = Calendar.getInstance()
        val byDow = mutableMapOf<Int, Int>()
        val byMonth = mutableMapOf<String, Int>()
        sorted.forEach { msg ->
            extractEmojis(msg.body).forEach { emoji ->
                emojiCounts[emoji] = (emojiCounts[emoji] ?: 0) + 1
            }
            cal.timeInMillis = msg.timestamp
            val dow = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            byDow[dow] = (byDow[dow] ?: 0) + 1
            val month = cal.get(Calendar.MONTH).toString()
            byMonth[month] = (byMonth[month] ?: 0) + 1
        }

        return ThreadStatsEntity(
            threadId = threadId,
            totalMessages = totalMessages,
            sentCount = sentCount,
            receivedCount = totalMessages - sentCount,
            firstMessageAt = sorted.first().timestamp,
            lastMessageAt = sorted.last().timestamp,
            activeDayCount = activeDays.size,
            longestStreakDays = computeLongestStreak(activeDays),
            avgResponseTimeMs = computeAvgResponseTime(sorted),
            topEmojisJson = emojiMapToJson(emojiCounts),
            byDayOfWeekJson = mapIntIntToJson(byDow),
            byMonthJson = mapStringIntToJson(byMonth),
            lastUpdatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun computeLongestStreakDays(threadId: Long): Int =
        computeLongestStreak(messageDao.getActiveDatesForThread(threadId).sorted())


    // --- JSON helpers (org.json is bundled with Android, no extra dependency needed) ---

    private fun emojiMapToJson(map: Map<String, Int>): String {
        val arr = JSONArray()
        map.entries.sortedByDescending { it.value }.take(20).forEach { (emoji, count) ->
            arr.put(JSONObject().apply { put("emoji", emoji); put("count", count) })
        }
        return arr.toString()
    }

    private fun emojiJsonToMap(json: String): MutableMap<String, Int> {
        val map = mutableMapOf<String, Int>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                map[obj.getString("emoji")] = obj.getInt("count")
            }
        } catch (_: Exception) {}
        return map
    }

    private fun mapIntIntToJson(map: Map<Int, Int>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k.toString(), v) }
        return obj.toString()
    }

    private fun jsonToMapIntInt(json: String): MutableMap<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { key -> map[key.toInt()] = obj.getInt(key) }
        } catch (_: Exception) {}
        return map
    }

    private fun mapStringIntToJson(map: Map<String, Int>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun jsonToMapStringInt(json: String): MutableMap<String, Int> {
        val map = mutableMapOf<String, Int>()
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { key -> map[key] = obj.getInt(key) }
        } catch (_: Exception) {}
        return map
    }
}

// ── Pure algorithms — internal so unit tests can reach them without org.json ──

internal fun computeLongestStreak(sortedDays: List<String>): Int {
    if (sortedDays.isEmpty()) return 0
    var maxStreak = 1
    var current = 1
    for (i in 1 until sortedDays.size) {
        val prev = java.time.LocalDate.parse(sortedDays[i - 1])
        val curr = java.time.LocalDate.parse(sortedDays[i])
        if (java.time.temporal.ChronoUnit.DAYS.between(prev, curr) == 1L) {
            current++
            if (current > maxStreak) maxStreak = current
        } else {
            current = 1
        }
    }
    return maxStreak
}

internal fun computeAvgResponseTime(sorted: List<MessageEntity>): Long {
    var total = 0L
    var count = 0
    for (i in 1 until sorted.size) {
        if (sorted[i - 1].isSent != sorted[i].isSent) {
            total += sorted[i].timestamp - sorted[i - 1].timestamp
            count++
        }
    }
    return if (count > 0) total / count else 0L
}

internal fun extractEmojis(text: String): List<String> {
    val result = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (isEmojiCodePoint(cp)) result.add(String(Character.toChars(cp)))
        i += Character.charCount(cp)
    }
    return result
}

internal fun isEmojiCodePoint(cp: Int): Boolean =
    cp in 0x1F000..0x1FAFF ||
    cp in 0x2600..0x27BF ||
    cp in 0x2300..0x23FF ||
    cp in 0x2B00..0x2BFF
