package com.plusorminustwo.postmark.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_NONE
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ReactionEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadStatsEntity
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostmarkDatabaseTest {

    private lateinit var db: PostmarkDatabase

    @Before
    fun createDb() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PostmarkDatabase::class.java)
            .addCallback(PostmarkDatabase.FTS_CALLBACK)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() = db.close()

    // ── Thread DAO ─────────────────────────────────────────────────────────

    @Test
    fun insertAndRetrieveThread() = runBlocking {
        val thread = thread(1)
        db.threadDao().insert(thread)
        val retrieved = db.threadDao().getById(1L)
        assertEquals("Alice", retrieved?.displayName)
    }

    @Test
    fun updateBackupPolicy() = runBlocking {
        db.threadDao().insert(thread(1))
        db.threadDao().updateBackupPolicy(1L, BackupPolicy.NEVER_INCLUDE)
        val retrieved = db.threadDao().getById(1L)
        assertEquals(BackupPolicy.NEVER_INCLUDE, retrieved?.backupPolicy)
    }

    @Test
    fun getThreadsForBackupExcludesNeverInclude() = runBlocking {
        db.threadDao().insertAll(listOf(
            thread(1, BackupPolicy.GLOBAL),
            thread(2, BackupPolicy.ALWAYS_INCLUDE),
            thread(3, BackupPolicy.NEVER_INCLUDE)
        ))
        val forBackup = db.threadDao().getThreadsForBackup()
        assertEquals(2, forBackup.size)
        assertTrue(forBackup.none { it.backupPolicy == BackupPolicy.NEVER_INCLUDE })
    }

    // ── Message DAO ────────────────────────────────────────────────────────

    @Test
    fun insertAndRetrieveMessages() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insertAll(listOf(msg(10, 1), msg(11, 1)))
        val msgs = db.messageDao().getByThread(1L)
        assertEquals(2, msgs.size)
    }

    @Test
    fun cascadeDeleteRemovesMessages() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1))
        db.threadDao().delete(thread(1))
        val msgs = db.messageDao().getByThread(1L)
        assertTrue(msgs.isEmpty())
    }

    // ── Reaction DAO ───────────────────────────────────────────────────────

    @Test
    fun insertAndRetrieveReaction() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1))
        db.reactionDao().insert(reaction(messageId = 10))
        val reactions = db.reactionDao().getByMessage(10L)
        assertEquals(1, reactions.size)
        assertEquals("❤️", reactions[0].emoji)
    }

    @Test
    fun deleteMessageCascadesToReactions() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1))
        db.reactionDao().insert(reaction(messageId = 10))
        db.messageDao().delete(msg(10, 1))
        assertTrue(db.reactionDao().getByMessage(10L).isEmpty())
    }

    @Test
    fun deleteReactionByEmojiAndSender() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1))
        db.reactionDao().insert(reaction(messageId = 10, emoji = "❤️", sender = "+1555"))
        db.reactionDao().insert(reaction(messageId = 10, emoji = "😂", sender = "+1555"))
        db.reactionDao().deleteByMessageSenderAndEmoji(10L, "+1555", "❤️")
        val remaining = db.reactionDao().getByMessage(10L)
        assertEquals(1, remaining.size)
        assertEquals("😂", remaining[0].emoji)
    }

    // ── ThreadStats DAO ────────────────────────────────────────────────────

    @Test
    fun upsertAndReadStats() = runBlocking {
        db.threadDao().insert(thread(1))
        val stats = ThreadStatsEntity(threadId = 1L, totalMessages = 42, sentCount = 20, receivedCount = 22)
        db.threadStatsDao().upsert(stats)
        val read = db.threadStatsDao().getByThread(1L)
        assertEquals(42, read?.totalMessages)
    }

    @Test
    fun globalCountsSumsAcrossThreads() = runBlocking {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.threadStatsDao().upsert(ThreadStatsEntity(threadId = 1L, totalMessages = 30, sentCount = 10, receivedCount = 20))
        db.threadStatsDao().upsert(ThreadStatsEntity(threadId = 2L, totalMessages = 70, sentCount = 40, receivedCount = 30))
        val global = db.threadStatsDao().getGlobalCounts()
        assertEquals(100, global?.totalMessages)
        assertEquals(50, global?.sentCount)
    }

    // ── FTS search via SearchDao ───────────────────────────────────────────

    @Test
    fun ftsInsertTriggerMakesBodySearchable() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1, body = "Hello world"))
        db.messageDao().insert(msg(11, 1, body = "Goodbye world"))

        // Word-start prefix query — matches "Hello" but not "world" mid-word
        val results = db.searchDao().searchMessages("^\"Hello\"*")
        assertEquals(1, results.size)
        assertEquals("Hello world", results[0].body)
    }

    @Test
    fun ftsSearchIsWordStartOnly() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1, body = "the cat sat"))
        // "he" should NOT match "the" (word-start only)
        val results = db.searchDao().searchMessages("^\"he\"*")
        assertTrue(results.isEmpty())
    }

    @Test
    fun ftsDeleteTriggerRemovesBodyFromIndex() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1, body = "unique phrase"))
        // Confirm it's searchable
        assertEquals(1, db.searchDao().searchMessages("^\"unique\"*").size)
        // Delete the message
        db.messageDao().delete(msg(10, 1, body = "unique phrase"))
        // Should no longer appear
        assertEquals(0, db.searchDao().searchMessages("^\"unique\"*").size)
    }

    @Test
    fun ftsUpdateTriggerReplacesOldBody() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1, body = "original text"))
        // Update the message
        db.messageDao().insertAll(listOf(msg(10, 1, body = "updated text"))) // REPLACE
        val oldResults = db.searchDao().searchMessages("^\"original\"*")
        val newResults = db.searchDao().searchMessages("^\"updated\"*")
        assertEquals("old body should be gone from FTS", 0, oldResults.size)
        assertEquals("new body should be indexed", 1, newResults.size)
    }

    @Test
    fun ftsSearchWithinThread() = runBlocking {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.messageDao().insert(msg(10, 1, body = "apple pie"))
        db.messageDao().insert(msg(11, 2, body = "apple cider"))
        val results = db.searchDao().searchMessagesInThread("^\"apple\"*", threadId = 1L)
        assertEquals(1, results.size)
        assertEquals(1L, results[0].threadId)
    }

    // ── New MessageDao methods ─────────────────────────────────────────────

    @Test
    fun getLatestNForThread_returnsNMostRecentInDescOrder() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insertAll(listOf(
            msg(1, 1, ts = 1000L),
            msg(2, 1, ts = 2000L),
            msg(3, 1, ts = 3000L),
            msg(4, 1, ts = 4000L)
        ))
        val result = db.messageDao().getLatestNForThread(1L, 2)
        assertEquals(2, result.size)
        assertEquals(4L, result[0].id)  // most recent first
        assertEquals(3L, result[1].id)
    }

    @Test
    fun getLatestNForThread_fewerMessagesThanN_returnsAll() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(1, 1, ts = 1000L))
        val result = db.messageDao().getLatestNForThread(1L, 5)
        assertEquals(1, result.size)
    }

    @Test
    fun getLatestBeforeForThread_returnsMessageStrictlyBeforeTimestamp() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insertAll(listOf(
            msg(1, 1, ts = 1000L),
            msg(2, 1, ts = 2000L),
            msg(3, 1, ts = 3000L)
        ))
        val result = db.messageDao().getLatestBeforeForThread(1L, 3000L)
        assertNotNull(result)
        assertEquals(2L, result!!.id)
    }

    @Test
    fun getLatestBeforeForThread_noMessageBefore_returnsNull() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(1, 1, ts = 5000L))
        val result = db.messageDao().getLatestBeforeForThread(1L, 5000L)
        assertNull(result)
    }

    @Test
    fun updateDeliveryStatus_persistsNewStatus() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(1, 1))
        assertEquals(DELIVERY_STATUS_NONE, db.messageDao().getById(1L)!!.deliveryStatus)

        db.messageDao().updateDeliveryStatus(1L, DELIVERY_STATUS_SENT)
        assertEquals(DELIVERY_STATUS_SENT, db.messageDao().getById(1L)!!.deliveryStatus)

        db.messageDao().updateDeliveryStatus(1L, DELIVERY_STATUS_DELIVERED)
        assertEquals(DELIVERY_STATUS_DELIVERED, db.messageDao().getById(1L)!!.deliveryStatus)
    }

    @Test
    fun deleteOptimisticMessages_removesNegativeIdRows_leavesPositiveIntact() = runBlocking {
        db.threadDao().insert(thread(1))
        // positive-ID real messages
        db.messageDao().insertAll(listOf(msg(1, 1, ts = 1000L), msg(2, 1, ts = 2000L)))
        // negative-ID optimistic messages
        db.messageDao().insertAll(listOf(msg(-100, 1, ts = 3000L), msg(-200, 1, ts = 4000L)))
        assertEquals(4, db.messageDao().countByThread(1L))

        db.messageDao().deleteOptimisticMessages(1L)

        val remaining = db.messageDao().getByThread(1L)
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.id > 0 })
    }

    @Test
    fun deleteOptimisticMessages_onlyAffectsSpecifiedThread() = runBlocking {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.messageDao().insert(msg(-1, 1, ts = 1000L))
        db.messageDao().insert(msg(-2, 2, ts = 1000L))

        db.messageDao().deleteOptimisticMessages(1L)

        assertNull(db.messageDao().getById(-1L))
        assertNotNull(db.messageDao().getById(-2L))
    }

    // ── MessageDao range queries (month-scoped heatmap) ────────────────────

    @Test
    fun observeMessagesInRange_noMessages_returnsEmpty() = runBlocking {
        db.threadDao().insert(thread(1))
        val result = db.messageDao().observeMessagesInRange(1000L, 2000L).first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun observeMessagesInRange_messageAtStartIncluded() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(1, 1, ts = 1000L))
        val result = db.messageDao().observeMessagesInRange(1000L, 2000L).first()
        assertEquals(1, result.size)
    }

    @Test
    fun observeMessagesInRange_messageAtEndExcluded() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(1, 1, ts = 2000L))
        val result = db.messageDao().observeMessagesInRange(1000L, 2000L).first()
        assertTrue("timestamp == endMs should be excluded", result.isEmpty())
    }

    @Test
    fun observeMessagesInRange_beforeRangeExcluded() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(1, 1, ts = 999L))
        val result = db.messageDao().observeMessagesInRange(1000L, 2000L).first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun observeMessagesInRange_onlyIncludesMessagesWithinBounds() = runBlocking {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.messageDao().insertAll(listOf(
            msg(1, 1, ts = 500L),   // before range
            msg(2, 1, ts = 1000L),  // at start — included
            msg(3, 1, ts = 1500L),  // inside — included
            msg(4, 1, ts = 1999L),  // inside — included
            msg(5, 1, ts = 2000L),  // at end — excluded
            msg(6, 2, ts = 1200L),  // different thread, inside — included
        ))
        val result = db.messageDao().observeMessagesInRange(1000L, 2000L).first()
        assertEquals(4, result.size)
        assertTrue(result.none { it.timestamp < 1000L || it.timestamp >= 2000L })
    }

    @Test
    fun observeMessagesInRangeForThread_onlyReturnsMatchingThread() = runBlocking {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.messageDao().insertAll(listOf(
            msg(1, 1, ts = 1000L),
            msg(2, 2, ts = 1100L),
            msg(3, 1, ts = 1200L),
            msg(4, 2, ts = 1300L)
        ))
        val result = db.messageDao().observeMessagesInRangeForThread(1L, 0L, 5000L).first()
        assertEquals(2, result.size)
        assertTrue(result.all { it.threadId == 1L })
    }

    @Test
    fun observeMessagesInRangeForThread_respectsTimestampBounds() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insertAll(listOf(
            msg(1, 1, ts = 500L),
            msg(2, 1, ts = 1000L),
            msg(3, 1, ts = 1500L),
            msg(4, 1, ts = 2500L)
        ))
        val result = db.messageDao().observeMessagesInRangeForThread(1L, 1000L, 2000L).first()
        assertEquals(2, result.size)
        assertEquals(setOf(1L, 2L), result.map { it.id - 1L + 1L }.toSet())
        assertTrue(result.all { it.timestamp >= 1000L && it.timestamp < 2000L })
    }

    @Test
    fun observeMessagesInRangeForThread_noMatchForWrongThread() = runBlocking {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.messageDao().insertAll(listOf(
            msg(1, 2, ts = 1000L),
            msg(2, 2, ts = 1500L)
        ))
        val result = db.messageDao().observeMessagesInRangeForThread(1L, 0L, 5000L).first()
        assertTrue(result.isEmpty())
    }

    // ── Factories ─────────────────────────────────────────────────────────

    private fun thread(id: Long, policy: BackupPolicy = BackupPolicy.GLOBAL) = ThreadEntity(
        id = id, displayName = "Alice", address = "+1555000$id",
        lastMessageAt = System.currentTimeMillis(), backupPolicy = policy
    )

    private fun msg(
        id: Long,
        threadId: Long,
        body: String = "test body",
        ts: Long = System.currentTimeMillis()
    ) = MessageEntity(
        id = id, threadId = threadId, address = "+1555",
        body = body, timestamp = ts, isSent = false, type = 1
    )

    private fun reaction(
        messageId: Long,
        emoji: String = "❤️",
        sender: String = "+1555"
    ) = ReactionEntity(
        messageId = messageId, senderAddress = sender,
        emoji = emoji, timestamp = System.currentTimeMillis(), rawText = "Loved 'test'"
    )
}
