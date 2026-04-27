package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.dao.GlobalStatsDao
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ThreadStatsDao
import com.plusorminustwo.postmark.data.db.entity.GlobalStatsEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadStatsEntity
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsUpdater @Inject constructor(
    private val messageDao: MessageDao,
    private val threadStatsDao: ThreadStatsDao,
    private val globalStatsDao: GlobalStatsDao
) {
    /**
     * Full recompute: recalculates stats for every thread in the database, then
     * aggregates into the global stats row. Safe to call on any coroutine dispatcher
     * (all work happens inside Room suspend functions).
     */
    suspend fun recomputeAll() {
        val threadIds = messageDao.getAllThreadIds()
        threadIds.forEach { threadId ->
            val messages = messageDao.getByThread(threadId)
            if (messages.isNotEmpty()) {
                val data = buildThreadStatsData(messages)
                threadStatsDao.upsert(data.toEntity(threadId))
            }
        }
        computeAndPersistGlobal(threadIds.size)
    }

    private suspend fun computeAndPersistGlobal(threadCount: Int) {
        val allMessages = messageDao.getAll()
        val data = buildGlobalStatsData(allMessages, threadCount)
        globalStatsDao.upsert(data.toEntity())
    }

    // ── ThreadStatsData → ThreadStatsEntity ──────────────────────────────────

    private fun ThreadStatsData.toEntity(threadId: Long) = ThreadStatsEntity(
        threadId          = threadId,
        totalMessages     = totalMessages,
        sentCount         = sentCount,
        receivedCount     = receivedCount,
        firstMessageAt    = firstMessageAt,
        lastMessageAt     = lastMessageAt,
        activeDayCount    = activeDayCount,
        longestStreakDays  = longestStreakDays,
        avgResponseTimeMs = avgResponseTimeMs,
        topEmojisJson     = emojiMapToJson(topEmojis),
        byDayOfWeekJson   = intArrayToJson(byDayOfWeek),
        byMonthJson       = intArrayToJson(byMonth),
        lastUpdatedAt     = System.currentTimeMillis()
    )

    // ── GlobalStatsData → GlobalStatsEntity ──────────────────────────────────

    private fun GlobalStatsData.toEntity() = GlobalStatsEntity(
        id                = 1,
        totalMessages     = totalMessages,
        sentCount         = sentCount,
        receivedCount     = receivedCount,
        threadCount       = threadCount,
        activeDayCount    = activeDayCount,
        longestStreakDays  = longestStreakDays,
        avgResponseTimeMs = avgResponseTimeMs,
        topEmojisJson     = emojiMapToJson(topEmojis),
        byDayOfWeekJson   = intArrayToJson(byDayOfWeek),
        byMonthJson       = intArrayToJson(byMonth),
        lastUpdatedAt     = System.currentTimeMillis()
    )

    // ── JSON helpers ─────────────────────────────────────────────────────────

    /**
     * Serialises the top-emoji map to a JSONArray:
     * [{"emoji":"😀","count":5}, ...], sorted descending, capped at 20 entries.
     */
    private fun emojiMapToJson(map: Map<String, Int>): String {
        val arr = JSONArray()
        map.entries.sortedByDescending { it.value }.take(20).forEach { (emoji, count) ->
            arr.put(JSONObject().apply { put("emoji", emoji); put("count", count) })
        }
        return arr.toString()
    }

    /**
     * Serialises an IntArray (index → count) to a JSONObject: {"0":5,"1":3,...}.
     * Only entries with count > 0 are written.
     */
    private fun intArrayToJson(array: IntArray): String {
        val obj = JSONObject()
        array.forEachIndexed { index, count ->
            if (count > 0) obj.put(index.toString(), count)
        }
        return obj.toString()
    }
}
