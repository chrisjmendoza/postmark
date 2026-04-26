package com.plusorminustwo.postmark.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.plusorminustwo.postmark.data.db.PostmarkDatabase
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class StatsUpdaterIntegrationTest {

    private lateinit var db: PostmarkDatabase
    private lateinit var updater: StatsUpdater

    private val DAY = 86_400_000L  // ms per day

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PostmarkDatabase::class.java)
            .addCallback(PostmarkDatabase.FTS_CALLBACK)
            .allowMainThreadQueries()
            .build()
        updater = StatsUpdater(db.messageDao(), db.threadStatsDao())
    }

    @After
    fun tearDown() = db.close()

    // ── computeForThread basics ────────────────────────────────────────────

    @Test
    fun computeForThread_emptyThread_doesNothing() = runBlocking {
        db.threadDao().insert(thread(1))
        updater.computeForThread(1L)
        assertNull(db.threadStatsDao().getByThread(1L))
    }

    @Test
    fun computeForThread_correctCounts() = runBlocking {
        insert(thread(1), msg(1, 1, isSent = true), msg(2, 1, isSent = false), msg(3, 1, isSent = true))
        updater.computeForThread(1L)
        db.threadStatsDao().getByThread(1L)!!.let {
            assertEquals(3, it.totalMessages)
            assertEquals(2, it.sentCount)
            assertEquals(1, it.receivedCount)
        }
    }

    @Test
    fun computeForThread_firstAndLastTimestamps() = runBlocking {
        val t1 = DAY; val t3 = DAY * 3
        insert(thread(1), msg(1, 1, ts = t1), msg(2, 1, ts = DAY * 2), msg(3, 1, ts = t3))
        updater.computeForThread(1L)
        db.threadStatsDao().getByThread(1L)!!.let {
            assertEquals(t1, it.firstMessageAt)
            assertEquals(t3, it.lastMessageAt)
        }
    }

    @Test
    fun computeForThread_activeDayCount_multipleMessagesPerDay() = runBlocking {
        // 2 messages on day 1, 1 message on day 3 → 2 active days
        insert(thread(1), msg(1, 1, ts = DAY), msg(2, 1, ts = DAY + 3600_000), msg(3, 1, ts = DAY * 3))
        updater.computeForThread(1L)
        assertEquals(2, db.threadStatsDao().getByThread(1L)!!.activeDayCount)
    }

    @Test
    fun computeForThread_longestStreak_fiveConsecutiveDays() = runBlocking {
        insert(thread(1))
        (1..5).forEachIndexed { i, day -> db.messageDao().insert(msg(i.toLong() + 1, 1, ts = DAY * day)) }
        updater.computeForThread(1L)
        assertEquals(5, db.threadStatsDao().getByThread(1L)!!.longestStreakDays)
    }

    @Test
    fun computeForThread_longestStreak_withGap() = runBlocking {
        insert(thread(1))
        listOf(1L, 2L, 3L, 5L, 6L).forEachIndexed { i, day ->
            db.messageDao().insert(msg(i.toLong() + 1, 1, ts = DAY * day))
        }
        updater.computeForThread(1L)
        assertEquals(3, db.threadStatsDao().getByThread(1L)!!.longestStreakDays)
    }

    @Test
    fun computeForThread_avgResponseTime() = runBlocking {
        // sent at 0, recv 1h later (pair 1: 3600s), sent 2h after recv (pair 2: 7200s) → avg 5400s
        insert(
            thread(1),
            msg(1, 1, isSent = true, ts = 0L),
            msg(2, 1, isSent = false, ts = 3_600_000L),
            msg(3, 1, isSent = true, ts = 10_800_000L)
        )
        updater.computeForThread(1L)
        assertEquals(5_400_000L, db.threadStatsDao().getByThread(1L)!!.avgResponseTimeMs)
    }

    @Test
    fun computeForThread_allSameSender_avgResponseTimeIsZero() = runBlocking {
        insert(thread(1), msg(1, 1, isSent = true, ts = 0L), msg(2, 1, isSent = true, ts = 1000L))
        updater.computeForThread(1L)
        assertEquals(0L, db.threadStatsDao().getByThread(1L)!!.avgResponseTimeMs)
    }

    @Test
    fun computeForThread_emojiCountedInJson() = runBlocking {
        insert(thread(1), msg(1, 1, body = "haha 😂 so funny 😂"))
        updater.computeForThread(1L)
        val arr = JSONArray(db.threadStatsDao().getByThread(1L)!!.topEmojisJson)
        val entry = arr.getJSONObject(0)
        assertEquals("😂", entry.getString("emoji"))
        assertEquals(2, entry.getInt("count"))
    }

    @Test
    fun computeForThread_noEmoji_topEmojisJsonIsEmptyArray() = runBlocking {
        insert(thread(1), msg(1, 1, body = "plain text, no emojis"))
        updater.computeForThread(1L)
        assertEquals("[]", db.threadStatsDao().getByThread(1L)!!.topEmojisJson)
    }

    @Test
    fun computeForThread_byDayOfWeek_mondayInBucket0() = runBlocking {
        insert(thread(1), msg(1, 1, ts = mondayTs()))
        updater.computeForThread(1L)
        val dow = JSONObject(db.threadStatsDao().getByThread(1L)!!.byDayOfWeekJson)
        assertTrue("key '0' (Monday) should exist", dow.has("0"))
        assertEquals(1, dow.getInt("0"))
    }

    @Test
    fun computeForThread_byMonth_januaryInBucket0() = runBlocking {
        insert(thread(1), msg(1, 1, ts = januaryTs()))
        updater.computeForThread(1L)
        val byMonth = JSONObject(db.threadStatsDao().getByThread(1L)!!.byMonthJson)
        assertTrue("key '0' (January) should exist", byMonth.has("0"))
        assertEquals(1, byMonth.getInt("0"))
    }

    @Test
    fun computeForAllThreads_populatesEveryThread() = runBlocking {
        db.threadDao().insertAll(listOf(thread(1), thread(2), thread(3)))
        listOf(1L, 2L, 3L).forEachIndexed { i, tid ->
            db.messageDao().insert(msg(i.toLong() + 1, tid, ts = DAY))
        }
        updater.computeForAllThreads(listOf(1L, 2L, 3L))
        assertNotNull(db.threadStatsDao().getByThread(1L))
        assertNotNull(db.threadStatsDao().getByThread(2L))
        assertNotNull(db.threadStatsDao().getByThread(3L))
    }

    // ── updateForNewMessage ────────────────────────────────────────────────

    @Test
    fun updateForNewMessage_noExistingStats_fallsBackToFullCompute() = runBlocking {
        insert(thread(1), msg(1, 1, ts = DAY))
        updater.updateForNewMessage(1L)
        assertNotNull(db.threadStatsDao().getByThread(1L))
        assertEquals(1, db.threadStatsDao().getByThread(1L)!!.totalMessages)
    }

    @Test
    fun updateForNewMessage_incrementsTotalAndSentCount() = runBlocking {
        insert(thread(1), msg(1, 1, isSent = true, ts = DAY))
        updater.computeForThread(1L)

        db.messageDao().insert(msg(2, 1, isSent = false, ts = DAY + 3_600_000))
        updater.updateForNewMessage(1L)

        db.threadStatsDao().getByThread(1L)!!.let {
            assertEquals(2, it.totalMessages)
            assertEquals(1, it.sentCount)
            assertEquals(1, it.receivedCount)
        }
    }

    @Test
    fun updateForNewMessage_newDay_incrementsActiveDayCount() = runBlocking {
        insert(thread(1), msg(1, 1, ts = DAY))
        updater.computeForThread(1L)
        assertEquals(1, db.threadStatsDao().getByThread(1L)!!.activeDayCount)

        db.messageDao().insert(msg(2, 1, ts = DAY * 2))
        updater.updateForNewMessage(1L)

        assertEquals(2, db.threadStatsDao().getByThread(1L)!!.activeDayCount)
    }

    @Test
    fun updateForNewMessage_sameDay_doesNotIncrementActiveDayCount() = runBlocking {
        insert(thread(1), msg(1, 1, ts = DAY))
        updater.computeForThread(1L)

        db.messageDao().insert(msg(2, 1, ts = DAY + 3_600_000))
        updater.updateForNewMessage(1L)

        assertEquals(1, db.threadStatsDao().getByThread(1L)!!.activeDayCount)
    }

    @Test
    fun updateForNewMessage_updatesLastMessageAt() = runBlocking {
        val t1 = DAY; val t2 = DAY * 2
        insert(thread(1), msg(1, 1, ts = t1))
        updater.computeForThread(1L)

        db.messageDao().insert(msg(2, 1, ts = t2))
        updater.updateForNewMessage(1L)

        assertEquals(t2, db.threadStatsDao().getByThread(1L)!!.lastMessageAt)
    }

    @Test
    fun updateForNewMessage_accumulatesEmojiCounts() = runBlocking {
        insert(thread(1), msg(1, 1, body = "😂"))
        updater.computeForThread(1L)

        db.messageDao().insert(msg(2, 1, body = "😂 again"))
        updater.updateForNewMessage(1L)

        val arr = JSONArray(db.threadStatsDao().getByThread(1L)!!.topEmojisJson)
        assertEquals("😂", arr.getJSONObject(0).getString("emoji"))
        assertEquals(2, arr.getJSONObject(0).getInt("count"))
    }

    @Test
    fun updateForNewMessage_incrementsByDayOfWeek() = runBlocking {
        insert(thread(1), msg(1, 1, ts = mondayTs()))
        updater.computeForThread(1L)

        // Second message also on a Monday (one week later)
        db.messageDao().insert(msg(2, 1, ts = mondayTs() + 7 * DAY))
        updater.updateForNewMessage(1L)

        val dow = JSONObject(db.threadStatsDao().getByThread(1L)!!.byDayOfWeekJson)
        assertEquals(2, dow.getInt("0"))
    }

    // ── global counts ──────────────────────────────────────────────────────

    @Test
    fun globalCounts_sumsAcrossAllThreadStats() = runBlocking {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        insert(thread(1), msg(1, 1, isSent = true, ts = DAY), msg(2, 1, isSent = false, ts = DAY + 1))
        insert(thread(2), msg(3, 2, isSent = true, ts = DAY))
        updater.computeForAllThreads(listOf(1L, 2L))

        val global = db.threadStatsDao().getGlobalCounts()!!
        assertEquals(3, global.totalMessages)
        assertEquals(2, global.sentCount)
        assertEquals(1, global.receivedCount)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private suspend fun insert(vararg entities: Any) {
        entities.forEach { e ->
            when (e) {
                is ThreadEntity -> db.threadDao().insert(e)
                is MessageEntity -> db.messageDao().insert(e)
            }
        }
    }

    private fun thread(id: Long) = ThreadEntity(
        id = id, displayName = "User $id", address = "+1555$id",
        lastMessageAt = System.currentTimeMillis(), backupPolicy = BackupPolicy.GLOBAL
    )

    private fun msg(
        id: Long,
        threadId: Long,
        isSent: Boolean = true,
        ts: Long = System.currentTimeMillis(),
        body: String = "test"
    ) = MessageEntity(
        id = id, threadId = threadId, address = "+1555",
        body = body, timestamp = ts, isSent = isSent, type = 1
    )

    /** Jan 1 2024 at noon local time — a Monday, Calendar.MONTH = 0 (January). */
    private fun mondayTs(): Long = Calendar.getInstance().apply {
        set(2024, Calendar.JANUARY, 1, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** A January timestamp for month-bucket tests. */
    private fun januaryTs(): Long = Calendar.getInstance().apply {
        set(2024, Calendar.JANUARY, 15, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
