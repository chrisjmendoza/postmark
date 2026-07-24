package com.plusorminustwo.postmark.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_NONE
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ReactionEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
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
    fun insertAndRetrieveThread() = runTest {
        val thread = thread(1)
        db.threadDao().insert(thread)
        val retrieved = db.threadDao().getById(1L)
        assertEquals("Alice", retrieved?.displayName)
    }

    @Test
    fun updateBackupPolicy() = runTest {
        db.threadDao().insert(thread(1))
        db.threadDao().updateBackupPolicy(1L, BackupPolicy.NEVER_INCLUDE)
        val retrieved = db.threadDao().getById(1L)
        assertEquals(BackupPolicy.NEVER_INCLUDE, retrieved?.backupPolicy)
    }

    @Test
    fun getThreadsForBackupExcludesNeverInclude() = runTest {
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
    fun insertAndRetrieveMessages() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insertAll(listOf(msg(10, 1), msg(11, 1)))
        val msgs = db.messageDao().getByThread(1L)
        assertEquals(2, msgs.size)
    }

    @Test
    fun cascadeDeleteRemovesMessages() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1))
        db.threadDao().delete(thread(1))
        val msgs = db.messageDao().getByThread(1L)
        assertTrue(msgs.isEmpty())
    }

    // ── Reaction DAO ───────────────────────────────────────────────────────

    @Test
    fun insertAndRetrieveReaction() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1))
        db.reactionDao().insert(reaction(messageId = 10))
        val reactions = db.reactionDao().getByMessage(10L)
        assertEquals(1, reactions.size)
        assertEquals("❤️", reactions[0].emoji)
    }

    @Test
    fun deleteMessageCascadesToReactions() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1))
        db.reactionDao().insert(reaction(messageId = 10))
        db.messageDao().delete(msg(10, 1))
        assertTrue(db.reactionDao().getByMessage(10L).isEmpty())
    }

    @Test
    fun deleteReactionByEmojiAndSender() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1))
        db.reactionDao().insert(reaction(messageId = 10, emoji = "❤️", sender = "+1555"))
        db.reactionDao().insert(reaction(messageId = 10, emoji = "😂", sender = "+1555"))
        db.reactionDao().deleteByMessageSenderAndEmoji(10L, "+1555", "❤️")
        val remaining = db.reactionDao().getByMessage(10L)
        assertEquals(1, remaining.size)
        assertEquals("😂", remaining[0].emoji)
    }

    @Test
    fun updateSpamFlagById() = runTest {
        db.threadDao().insert(thread(1))
        assertEquals(false, db.threadDao().getById(1L)?.isSpam)

        db.threadDao().updateSpam(1L, true)
        assertEquals(true, db.threadDao().getById(1L)?.isSpam)

        db.threadDao().updateSpam(1L, false)
        assertEquals(false, db.threadDao().getById(1L)?.isSpam)
    }

    @Test
    fun observeNonSpamAndObserveSpamPartitionThreads() = runTest {
        db.threadDao().insert(thread(1))  // stays in the main list
        db.threadDao().insert(thread(2))  // reported as spam
        db.threadDao().updateSpam(2L, true)

        val nonSpam = db.threadDao().observeNonSpam().first()
        assertEquals(listOf(1L), nonSpam.map { it.id })

        val spam = db.threadDao().observeSpam().first()
        assertEquals(listOf(2L), spam.map { it.id })

        // observeAll still returns everything (Stats path).
        assertEquals(setOf(1L, 2L), db.threadDao().observeAll().first().map { it.id }.toSet())
    }

    // ── FTS search via SearchDao ───────────────────────────────────────────

    @Test
    fun ftsInsertTriggerMakesBodySearchable() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1, body = "Hello world"))
        db.messageDao().insert(msg(11, 1, body = "Goodbye world"))

        // Word-start prefix query — matches "Hello" but not "world" mid-word
        val results = db.searchDao().searchMessagesFiltered("^\"Hello\"*")
        assertEquals(1, results.size)
        assertEquals("Hello world", results[0].body)
    }

    @Test
    fun ftsSearchIsWordStartOnly() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(10, 1, body = "the cat sat"))
        // "he" should NOT match "the" (word-start only)
        val results = db.searchDao().searchMessagesFiltered("^\"he\"*")
        assertTrue(results.isEmpty())
    }

    @Test
    fun ftsDeleteTriggerRemovesBodyFromIndex() = runTest {
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
    fun ftsUpdateTriggerReplacesOldBody() = runTest {
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
    fun ftsSearchWithinThread() = runTest {
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
    fun updateDeliveryStatus_persistsNewStatus() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(1, 1))
        assertEquals(DELIVERY_STATUS_NONE, db.messageDao().getById(1L)!!.deliveryStatus)

        db.messageDao().updateDeliveryStatus(1L, DELIVERY_STATUS_SENT)
        assertEquals(DELIVERY_STATUS_SENT, db.messageDao().getById(1L)!!.deliveryStatus)

        db.messageDao().updateDeliveryStatus(1L, DELIVERY_STATUS_DELIVERED)
        assertEquals(DELIVERY_STATUS_DELIVERED, db.messageDao().getById(1L)!!.deliveryStatus)
    }

    @Test
    fun deleteOptimisticMessages_removesNegativeIdRows_leavesPositiveIntact() = runTest {
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
    fun deleteOptimisticMessages_onlyAffectsSpecifiedThread() = runTest {
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
    fun deleteOptimisticMessages_smsScope_preservesPendingOptimisticMms() = runTest {
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
    fun deleteOptimisticMessages_mmsScope_preservesPendingOptimisticSms() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(-2000, 1, ts = 1000L, isSent = true, isMms = true))
        db.messageDao().insert(msg(-1000, 1, ts = 2000L, isSent = true))

        db.messageDao().deleteOptimisticMessages(1L, isMms = true)

        assertNotNull(db.messageDao().getById(-1000L))         // SMS temp row survives
        assertNull(db.messageDao().getById(-2000L))            // MMS temp row gone
    }

    @Test
    fun getOptimisticSent_mmsScope_returnsMmsRowDespiteNewerOptimisticSms() = runTest {
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
    fun getOptimisticSent_mmsScope_noOptimisticMms_returnsNull() = runTest {
        db.threadDao().insert(thread(1))
        // Only an optimistic SMS exists — MMS-scoped reads must not pick it up.
        db.messageDao().insert(msg(-1000, 1, ts = 1000L, isSent = true))

        assertNull(db.messageDao().getOptimisticSentId(1L, isMms = true))
        assertNull(db.messageDao().getOptimisticSentDeliveryStatus(1L, isMms = true))
    }

    // ── MessageDao range queries (month-scoped heatmap) ────────────────────

    @Test
    fun observeMessagesInRange_noMessages_returnsEmpty() = runTest {
        db.threadDao().insert(thread(1))
        val result = db.messageDao().observeMessagesInRange(1000L, 2000L).first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun observeMessagesInRange_messageAtStartIncluded() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(1, 1, ts = 1000L))
        val result = db.messageDao().observeMessagesInRange(1000L, 2000L).first()
        assertEquals(1, result.size)
    }

    @Test
    fun observeMessagesInRange_messageAtEndExcluded() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(1, 1, ts = 2000L))
        val result = db.messageDao().observeMessagesInRange(1000L, 2000L).first()
        assertTrue("timestamp == endMs should be excluded", result.isEmpty())
    }

    @Test
    fun observeMessagesInRange_beforeRangeExcluded() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insert(msg(1, 1, ts = 999L))
        val result = db.messageDao().observeMessagesInRange(1000L, 2000L).first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun observeMessagesInRange_onlyIncludesMessagesWithinBounds() = runTest {
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
    fun observeMessagesInRangeForThread_onlyReturnsMatchingThread() = runTest {
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
    fun observeMessagesInRangeForThread_respectsTimestampBounds() = runTest {
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
    fun observeMessagesInRangeForThread_noMatchForWrongThread() = runTest {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.messageDao().insertAll(listOf(
            msg(1, 2, ts = 1000L),
            msg(2, 2, ts = 1500L)
        ))
        val result = db.messageDao().observeMessagesInRangeForThread(1L, 0L, 5000L).first()
        assertTrue(result.isEmpty())
    }

    // ── StatsDao SQL aggregation (parity with Kotlin over the same rows) ────
    // Proves the GROUP BY / SUM / MIN / MAX projections agree with an in-memory
    // Kotlin aggregation of the identical inserted rows. Device run pending; this
    // is the SQL half of the "prove parity with tests" requirement.

    @Test
    fun statsThreadAggregates_matchKotlinAggregation() = runTest {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.messageDao().insertAll(listOf(
            msg(1, 1, ts = 100L, isSent = true),
            msg(2, 1, ts = 400L, isSent = false),
            msg(3, 1, ts = 250L, isSent = true),
            msg(4, 2, ts = 900L, isSent = false)
        ))

        val rows = db.statsDao().observeThreadAggregates().first().associateBy { it.threadId }

        // Thread 1: 3 msgs, 2 sent, min 100, max 400
        assertEquals(3, rows.getValue(1L).totalMessages)
        assertEquals(2, rows.getValue(1L).sentCount)
        assertEquals(100L, rows.getValue(1L).firstMessageAt)
        assertEquals(400L, rows.getValue(1L).lastMessageAt)
        // Thread 2: 1 msg, 0 sent
        assertEquals(1, rows.getValue(2L).totalMessages)
        assertEquals(0, rows.getValue(2L).sentCount)
        assertEquals(900L, rows.getValue(2L).firstMessageAt)
        assertEquals(900L, rows.getValue(2L).lastMessageAt)
    }

    @Test
    fun statsGlobalAggregates_matchKotlinAggregation() = runTest {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        val inserted = listOf(
            msg(1, 1, ts = 100L, isSent = true),
            msg(2, 1, ts = 400L, isSent = false),
            msg(3, 2, ts = 900L, isSent = true)
        )
        db.messageDao().insertAll(inserted)

        val g = db.statsDao().observeGlobalAggregates().first()
        assertEquals(inserted.size, g.totalMessages)
        assertEquals(inserted.count { it.isSent }, g.sentCount)
        assertEquals(inserted.minOf { it.timestamp }, g.firstMessageAt)
        assertEquals(inserted.maxOf { it.timestamp }, g.lastMessageAt)
    }

    @Test
    fun statsGlobalAggregates_emptyTableIsZero() = runTest {
        val g = db.statsDao().observeGlobalAggregates().first()
        assertEquals(0, g.totalMessages)
        assertEquals(0, g.sentCount)
        assertEquals(0L, g.firstMessageAt)
        assertEquals(0L, g.lastMessageAt)
    }

    @Test
    fun statsMessageMetas_projectEveryRow() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insertAll(listOf(
            msg(1, 1, ts = 100L, isSent = true),
            msg(2, 1, ts = 200L, isSent = false)
        ))
        val metas = db.statsDao().observeMessageMetas().first().sortedBy { it.timestamp }
        assertEquals(2, metas.size)
        assertEquals(100L, metas[0].timestamp)
        assertTrue(metas[0].isSent)
        assertEquals(1L, metas[0].threadId)
        assertFalse(metas[1].isSent)
    }

    @Test
    fun statsEmojiBodies_excludeEmptyBodies() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insertAll(listOf(
            msg(1, 1, body = "hello 😂"),
            msg(2, 1, body = ""),          // empty — excluded
            msg(3, 1, body = "hi")
        ))
        val bodies = db.statsDao().observeEmojiBodies().first()
        assertEquals(2, bodies.size)
        assertTrue(bodies.none { it.body.isEmpty() })
    }

    @Test
    fun statsReactionEmojisByThread_joinsThroughMessages() = runTest {
        db.threadDao().insertAll(listOf(thread(1), thread(2)))
        db.messageDao().insertAll(listOf(msg(10, 1), msg(20, 2)))
        db.reactionDao().insert(reaction(messageId = 10, emoji = "❤️"))
        db.reactionDao().insert(reaction(messageId = 20, emoji = "👍"))

        val rows = db.statsDao().observeReactionEmojisByThread().first()
        assertEquals(2, rows.size)
        assertEquals("❤️", rows.first { it.threadId == 1L }.emoji)
        assertEquals("👍", rows.first { it.threadId == 2L }.emoji)
    }

    @Test
    fun statsMessageMetasInRange_respectsBounds() = runTest {
        db.threadDao().insert(thread(1))
        db.messageDao().insertAll(listOf(
            msg(1, 1, ts = 500L),
            msg(2, 1, ts = 1000L),   // at start — included
            msg(3, 1, ts = 1999L),   // inside
            msg(4, 1, ts = 2000L)    // at end — excluded
        ))
        val metas = db.statsDao().observeMessageMetasInRange(1000L, 2000L).first()
        assertEquals(2, metas.size)
        assertTrue(metas.all { it.timestamp in 1000L until 2000L })
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
