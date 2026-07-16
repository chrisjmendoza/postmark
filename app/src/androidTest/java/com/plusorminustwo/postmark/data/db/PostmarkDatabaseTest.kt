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

    // ── FTS search via SearchDao ───────────────────────────────────────────

    @Test
    fun ftsInsertTriggerMakesBodySearchable() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1, body = "Hello world"))
        db.messageDao().insert(msg(11, 1, body = "Goodbye world"))

        // Word-start prefix query — matches "Hello" but not "world" mid-word
        val results = db.searchDao().searchMessagesFiltered("^\"Hello\"*")
        assertEquals(1, results.size)
        assertEquals("Hello world", results[0].body)
    }

    @Test
    fun ftsSearchIsWordStartOnly() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1, body = "the cat sat"))
        // "he" should NOT match "the" (word-start only)
        val results = db.searchDao().searchMessagesFiltered("^\"he\"*")
        assertTrue(results.isEmpty())
    }

    @Test
    fun ftsDeleteTriggerRemovesBodyFromIndex() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1, body = "unique phrase"))
        // Confirm it's searchable
        assertEquals(1, db.searchDao().searchMessagesFiltered("^\"unique\"*").size)
        // Delete the message
        db.messageDao().delete(msg(10, 1, body = "unique phrase"))
        // Should no longer appear
        assertEquals(0, db.searchDao().searchMessagesFiltered("^\"unique\"*").size)
    }

    @Test
    fun ftsUpdateTriggerReplacesOldBody() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1, body = "original text"))
        // Update the message
        db.messageDao().insertAll(listOf(msg(10, 1, body = "updated text"))) // REPLACE
        val oldResults = db.searchDao().searchMessagesFiltered("^\"original\"*")
        val newResults = db.searchDao().searchMessagesFiltered("^\"updated\"*")
        assertEquals("old body should be gone from FTS", 0, oldResults.size)
        assertEquals("new body should be indexed", 1, newResults.size)
    }

    @Test
    fun ftsSearchWithinThread() = runBlocking {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.messageDao().insert(msg(10, 1, body = "apple pie"))
        db.messageDao().insert(msg(11, 2, body = "apple cider"))
        val results = db.searchDao().searchMessagesFiltered("^\"apple\"*", threadId = 1L)
        assertEquals(1, results.size)
        assertEquals(1L, results[0].threadId)
    }

    // ── MessageDao methods ─────────────────────────────────────────────────
    // (getLatestNForThread / getLatestBeforeForThread tests deleted July 15 —
    // those DAO methods were removed in the July 14 dead-code sweep, fable-analysis
    // #27, which left this androidTest source set uncompilable.)

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

        db.messageDao().deleteOptimisticMessages(1L, isMms = false)

        val remaining = db.messageDao().getByThread(1L)
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.id > 0 })
    }

    @Test
    fun deleteOptimisticMessages_onlyAffectsSpecifiedThread() = runBlocking {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.messageDao().insert(msg(-1, 1, ts = 1000L))
        db.messageDao().insert(msg(-2, 2, ts = 1000L))

        db.messageDao().deleteOptimisticMessages(1L, isMms = false)

        assertNull(db.messageDao().getById(-1L))
        assertNotNull(db.messageDao().getById(-2L))
    }

    /* ── Regression: SMS-after-MMS race (sent image vanished from thread) ──
     *
     * Repro: user sends an MMS (optimistic row, isMms = 1, still pending because the
     * MMS round-trip takes seconds), then immediately sends an SMS. The SMS's real
     * row syncs in well under a second, and syncLatestSms()'s cleanup used to delete
     * EVERY negative-ID row in the thread — including the pending MMS bubble — so
     * the image disappeared before syncLatestMms() could import its real row.
     * The queries are now scoped by isMms, mirroring getMaxId()/getMaxMmsId(). */

    @Test
    fun deleteOptimisticMessages_smsScope_preservesPendingOptimisticMms() = runBlocking {
        db.threadDao().insert(thread(1))
        // Optimistic MMS (image) sent first, optimistic SMS sent moments later.
        db.messageDao().insert(msg(-2000, 1, ts = 1000L, isSent = true, isMms = true,
            attachmentUri = "content://cache/img.jpg"))
        db.messageDao().insert(msg(-1000, 1, ts = 2000L, isSent = true))

        // syncLatestSms() imported the SMS's real row and clears optimistic SMS rows only.
        db.messageDao().deleteOptimisticMessages(1L, isMms = false)

        assertNull(db.messageDao().getById(-1000L))            // SMS temp row gone
        assertNotNull(db.messageDao().getById(-2000L))         // MMS image bubble survives
    }

    @Test
    fun deleteOptimisticMessages_mmsScope_preservesPendingOptimisticSms() = runBlocking {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(-2000, 1, ts = 1000L, isSent = true, isMms = true))
        db.messageDao().insert(msg(-1000, 1, ts = 2000L, isSent = true))

        db.messageDao().deleteOptimisticMessages(1L, isMms = true)

        assertNotNull(db.messageDao().getById(-1000L))         // SMS temp row survives
        assertNull(db.messageDao().getById(-2000L))            // MMS temp row gone
    }

    @Test
    fun getOptimisticSent_mmsScope_returnsMmsRowDespiteNewerOptimisticSms() = runBlocking {
        db.threadDao().insert(thread(1))
        // MMS created first → more-negative id. SMS created after → larger id, so an
        // unscoped ORDER BY id DESC LIMIT 1 would wrongly return the SMS row.
        db.messageDao().insert(msg(-2000, 1, ts = 1000L, isSent = true, isMms = true,
            deliveryStatus = DELIVERY_STATUS_SENT, attachmentUri = "content://cache/img.jpg"))
        db.messageDao().insert(msg(-1000, 1, ts = 2000L, isSent = true,
            deliveryStatus = DELIVERY_STATUS_NONE))

        assertEquals(-2000L, db.messageDao().getOptimisticSentId(1L, isMms = true))
        assertEquals(DELIVERY_STATUS_SENT,
            db.messageDao().getOptimisticSentDeliveryStatus(1L, isMms = true))
        // The attachment transfer path reads the full row via getById(optimisticId).
        assertEquals("content://cache/img.jpg",
            db.messageDao().getById(-2000L)?.attachmentUri)
    }

    @Test
    fun getOptimisticSent_mmsScope_noOptimisticMms_returnsNull() = runBlocking {
        db.threadDao().insert(thread(1))
        // Only an optimistic SMS exists — MMS-scoped reads must not pick it up.
        db.messageDao().insert(msg(-1000, 1, ts = 1000L, isSent = true))

        assertNull(db.messageDao().getOptimisticSentId(1L, isMms = true))
        assertNull(db.messageDao().getOptimisticSentDeliveryStatus(1L, isMms = true))
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
        ts: Long = System.currentTimeMillis(),
        isSent: Boolean = false,
        isMms: Boolean = false,
        deliveryStatus: Int = DELIVERY_STATUS_NONE,
        attachmentUri: String? = null
    ) = MessageEntity(
        id = id, threadId = threadId, address = "+1555",
        body = body, timestamp = ts, isSent = isSent, type = 1,
        isMms = isMms, deliveryStatus = deliveryStatus, attachmentUri = attachmentUri
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
