package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.dao.EmojiCount
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.dao.UnreadCount
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ReactionEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import com.plusorminustwo.postmark.search.parser.AndroidReactionParser
import com.plusorminustwo.postmark.search.parser.AppleReactionParser
import com.plusorminustwo.postmark.search.parser.ReactionFallbackParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ReactionResolver] — the shared full-history reaction resolution pass
 * run by SmsHistoryImportWorker (after BOTH the SMS and MMS imports) and by
 * DevOptionsViewModel.reprocessReactions().
 *
 * The key regression these tests guard: the candidate pool must span both transports.
 * A reaction quoting an MMS-originated message, and a reaction fallback that itself
 * arrived as MMS, must both resolve into Reaction entities instead of remaining
 * visible text bubbles.
 *
 * Uses real parsers and repositories over in-memory fake DAOs (no mocking libraries,
 * per project convention). Apple verb patterns are supplied directly so no Android
 * Context / asset loading is needed.
 */
class ReactionResolverTest {

    private lateinit var messageDao: InMemoryMessageDao
    private lateinit var reactionDao: InMemoryReactionDao
    private lateinit var threadDao: RecordingThreadDao
    private lateinit var resolver: ReactionResolver

    @Before
    fun setUp() {
        messageDao = InMemoryMessageDao()
        reactionDao = InMemoryReactionDao()
        threadDao = RecordingThreadDao()
        val appleParser = AppleReactionParser(patternsProvider = {
            listOf(AppleReactionParser.ReactionPattern("👍", listOf("Liked"), listOf("Removed a like from")))
        })
        resolver = ReactionResolver(
            MessageRepository(messageDao, reactionDao),
            ThreadRepository(threadDao),
            ReactionFallbackParser(AndroidReactionParser(), appleParser)
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun msg(
        id: Long,
        body: String,
        timestamp: Long,
        isSent: Boolean = false,
        isMms: Boolean = false,
        address: String = "+15551234567",
        threadId: Long = 1L
    ) = MessageEntity(
        id = id, threadId = threadId, address = address, body = body,
        timestamp = timestamp, isSent = isSent, isMms = isMms
    )

    // ── cross-transport resolution (the first-launch import bug) ─────────────

    @Test
    fun `SMS fallback reacting to an MMS message resolves once both transports are present`() = runTest {
        val mmsId = MMS_ID_OFFSET + 5
        messageDao.seed(
            msg(mmsId, "Check out this sunset", timestamp = 1_000, isMms = true),
            msg(42, "👍 to \"Check out this sunset\"", timestamp = 2_000)
        )

        val result = resolver.resolveAll()

        assertEquals(ReactionResolver.Result(inserted = 1, removed = 1), result)
        val reaction = reactionDao.rows.single()
        assertEquals(mmsId, reaction.messageId)
        assertEquals("👍", reaction.emoji)
        assertEquals("+15551234567", reaction.senderAddress)
        // The fallback bubble is gone; the MMS original remains.
        assertFalse(messageDao.rows.containsKey(42L))
        assertTrue(messageDao.rows.containsKey(mmsId))
    }

    @Test
    fun `fallback delivered as MMS resolves into a reaction instead of remaining a bubble`() = runTest {
        val mmsFallbackId = MMS_ID_OFFSET + 9
        messageDao.seed(
            msg(7, "Dinner at 7?", timestamp = 1_000),
            msg(mmsFallbackId, "❤️ to \"Dinner at 7?\"", timestamp = 2_000, isSent = true, isMms = true)
        )

        val result = resolver.resolveAll()

        assertEquals(ReactionResolver.Result(inserted = 1, removed = 1), result)
        val reaction = reactionDao.rows.single()
        assertEquals(7L, reaction.messageId)
        assertEquals("❤️", reaction.emoji)
        assertEquals(SELF_ADDRESS, reaction.senderAddress)
        assertFalse(messageDao.rows.containsKey(mmsFallbackId))
    }

    @Test
    fun `Apple-format fallback also matches an MMS-originated message`() = runTest {
        val mmsId = MMS_ID_OFFSET + 3
        messageDao.seed(
            msg(mmsId, "Check out this sunset", timestamp = 1_000, isMms = true),
            msg(50, "Liked \"Check out this sunset\"", timestamp = 2_000)
        )

        val result = resolver.resolveAll()

        assertEquals(ReactionResolver.Result(inserted = 1, removed = 1), result)
        assertEquals(mmsId, reactionDao.rows.single().messageId)
        assertEquals("👍", reactionDao.rows.single().emoji)
    }

    // ── unresolved / removal / dedup semantics ───────────────────────────────

    @Test
    fun `unresolved fallback stays as a visible bubble`() = runTest {
        messageDao.seed(
            msg(7, "Dinner at 7?", timestamp = 1_000),
            msg(8, "👍 to \"text that matches nothing\"", timestamp = 2_000)
        )

        val result = resolver.resolveAll()

        assertEquals(ReactionResolver.Result(inserted = 0, removed = 0), result)
        assertTrue(reactionDao.rows.isEmpty())
        assertTrue(messageDao.rows.containsKey(8L))
    }

    @Test
    fun `removal fallback deletes the existing reaction and its bubble`() = runTest {
        messageDao.seed(
            msg(7, "Dinner at 7?", timestamp = 1_000),
            msg(9, "❤️ to \"Dinner at 7?\" removed", timestamp = 3_000)
        )
        reactionDao.rows += ReactionEntity(
            id = 1, messageId = 7, senderAddress = "+15551234567",
            emoji = "❤️", timestamp = 2_000, rawText = "❤️ to \"Dinner at 7?\""
        )

        val result = resolver.resolveAll()

        assertEquals(ReactionResolver.Result(inserted = 0, removed = 1), result)
        assertTrue(reactionDao.rows.isEmpty())
        assertFalse(messageDao.rows.containsKey(9L))
    }

    @Test
    fun `duplicate reaction is not inserted twice`() = runTest {
        messageDao.seed(
            msg(7, "Dinner at 7?", timestamp = 1_000),
            msg(9, "❤️ to \"Dinner at 7?\"", timestamp = 3_000)
        )
        reactionDao.rows += ReactionEntity(
            id = 1, messageId = 7, senderAddress = "+15551234567",
            emoji = "❤️", timestamp = 2_000, rawText = "❤️ to \"Dinner at 7?\""
        )

        val result = resolver.resolveAll()

        assertEquals(ReactionResolver.Result(inserted = 0, removed = 0), result)
        assertEquals(1, reactionDao.rows.size)
    }

    @Test
    fun `thread preview repaired after fallback deletion`() = runTest {
        messageDao.seed(
            msg(7, "Dinner at 7?", timestamp = 1_000),
            msg(9, "❤️ to \"Dinner at 7?\"", timestamp = 3_000)
        )

        resolver.resolveAll()

        // After the fallback (the newest message) is deleted, the preview must point
        // back at the real latest message.
        assertEquals(1L to 1_000L, threadDao.lastMessageAtUpdate)
        assertEquals(1L to "Dinner at 7?", threadDao.lastPreviewUpdate)
    }
}

// ── Fakes (scoped to this file, per project convention — no mocking libraries) ──

/** MessageDao backed by an in-memory map. Only the methods ReactionResolver reaches are real. */
private class InMemoryMessageDao : MessageDao {
    val rows = linkedMapOf<Long, MessageEntity>()

    fun seed(vararg messages: MessageEntity) = messages.forEach { rows[it.id] = it }

    override suspend fun getByThread(threadId: Long): List<MessageEntity> =
        rows.values.filter { it.threadId == threadId }.sortedBy { it.timestamp }
    override suspend fun getAllThreadIds(): List<Long> = rows.values.map { it.threadId }.distinct()
    override suspend fun deleteById(messageId: Long) { rows.remove(messageId) }
    override suspend fun getLatestNonReactionForThread(threadId: Long): MessageEntity? =
        rows.values.filter { it.threadId == threadId }.maxByOrNull { it.timestamp }
    override suspend fun insert(message: MessageEntity): Long { rows[message.id] = message; return message.id }
    override suspend fun insertAll(messages: List<MessageEntity>) = messages.forEach { rows[it.id] = it }

    override fun observeByThread(threadId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
    override fun observeMessagesFrom(startMs: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
    override fun observeMessagesFromForThread(threadId: Long, startMs: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
    override fun observeMessagesInRange(startMs: Long, endMs: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
    override fun observeMessagesInRangeForThread(threadId: Long, startMs: Long, endMs: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
    override suspend fun getById(messageId: Long): MessageEntity? = rows[messageId]
    override suspend fun delete(message: MessageEntity) { rows.remove(message.id) }
    override suspend fun deleteByThread(threadId: Long) = Unit
    override suspend fun countByThread(threadId: Long): Int = 0
    override suspend fun getByThreadAndDateRange(threadId: Long, startMs: Long, endMs: Long): List<MessageEntity> = emptyList()
    override suspend fun getActiveDatesForThread(threadId: Long): List<String> = emptyList()
    override suspend fun getLatestForThread(threadId: Long): MessageEntity? = null
    override suspend fun getLatestNForThread(threadId: Long, n: Int): List<MessageEntity> = emptyList()
    override suspend fun getLatestBeforeForThread(threadId: Long, timestamp: Long): MessageEntity? = null
    override suspend fun updateDeliveryStatus(messageId: Long, status: Int) = Unit
    override suspend fun updateThreadId(messageId: Long, threadId: Long) = Unit
    override suspend fun updateAttachments(messageId: Long, attachmentsJson: String?, firstUri: String?, firstMime: String?) = Unit
    override suspend fun deleteOptimisticMessages(threadId: Long, isMms: Boolean) = Unit
    override suspend fun getOptimisticSentDeliveryStatus(threadId: Long, isMms: Boolean): Int? = null
    override suspend fun getOptimisticSentId(threadId: Long, isMms: Boolean): Long? = null
    override suspend fun deleteAll() = Unit
    override suspend fun getAll(): List<MessageEntity> = rows.values.toList()
    override suspend fun getMaxId(): Long? = null
    override suspend fun getMaxMmsId(): Long? = null
    override suspend fun getMinMmsId(): Long? = null
    override suspend fun hasAnyMessages(): Boolean = rows.isNotEmpty()
    override suspend fun getMaxRestoredId(): Long? = null
    override suspend fun markAllRead(threadId: Long) = Unit
    override fun observeUnreadCounts(): Flow<List<UnreadCount>> = flowOf(emptyList())
    override fun observeMediaMessages(threadId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
    override suspend fun updateStarred(messageId: Long, isStarred: Boolean) = Unit
    override fun observeStarredMedia(): Flow<List<MessageEntity>> = flowOf(emptyList())
}

/** ReactionDao backed by an in-memory list with real insert / count / delete semantics. */
private class InMemoryReactionDao : ReactionDao {
    val rows = mutableListOf<ReactionEntity>()
    private var nextId = 1L

    override suspend fun insert(reaction: ReactionEntity): Long {
        val id = nextId++
        rows += reaction.copy(id = id)
        return id
    }
    override suspend fun countByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String): Int =
        rows.count { it.messageId == messageId && it.senderAddress == senderAddress && it.emoji == emoji }
    override suspend fun deleteByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String) {
        rows.removeAll { it.messageId == messageId && it.senderAddress == senderAddress && it.emoji == emoji }
    }

    override fun observeAll(): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override suspend fun getAll(): List<ReactionEntity> = rows.toList()
    override fun observeTopEmojisBySender(senderAddress: String): Flow<List<EmojiCount>> = flowOf(emptyList())
    override fun observeDistinctEmojis(): Flow<List<String>> = flowOf(emptyList())
    override fun observeByMessage(messageId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override fun observeByThread(threadId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override suspend fun getByThread(threadId: Long): List<ReactionEntity> = emptyList()
    override suspend fun getByMessage(messageId: Long): List<ReactionEntity> = emptyList()
    override suspend fun delete(reaction: ReactionEntity) { rows.removeAll { it.id == reaction.id } }
    override suspend fun getByEmoji(emoji: String): List<ReactionEntity> = emptyList()
    override suspend fun getTopEmojis(limit: Int): List<EmojiCount> = emptyList()
    override suspend fun deleteAll() { rows.clear() }
}

/** ThreadDao that records preview repairs; everything else is a no-op stub. */
private class RecordingThreadDao : ThreadDao {
    var lastMessageAtUpdate: Pair<Long, Long>? = null
    var lastPreviewUpdate: Pair<Long, String>? = null

    override suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) {
        lastMessageAtUpdate = threadId to timestamp
    }
    override suspend fun updateLastMessagePreview(threadId: Long, preview: String) {
        lastPreviewUpdate = threadId to preview
    }

    override fun observeAll(): Flow<List<ThreadEntity>> = flowOf(emptyList())
    override suspend fun getById(threadId: Long): ThreadEntity? = null
    override fun observeById(threadId: Long): Flow<ThreadEntity?> = flowOf(null)
    override suspend fun insert(thread: ThreadEntity) = Unit
    override suspend fun insertAll(threads: List<ThreadEntity>) = Unit
    override suspend fun insertIgnore(thread: ThreadEntity) = Unit
    override suspend fun insertAllIgnore(threads: List<ThreadEntity>) = Unit
    override suspend fun update(thread: ThreadEntity) = Unit
    override suspend fun delete(thread: ThreadEntity) = Unit
    override suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) = Unit
    override suspend fun updateMuted(threadId: Long, isMuted: Boolean) = Unit
    override suspend fun updatePinned(threadId: Long, isPinned: Boolean) = Unit
    override suspend fun updateNotificationsEnabled(threadId: Long, enabled: Boolean) = Unit
    override suspend fun isNotificationsEnabledByAddress(address: String): Boolean? = null
    override suspend fun getThreadsForBackup(): List<ThreadEntity> = emptyList()
    override suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity> = emptyList()
    override suspend fun isMutedByAddress(address: String): Boolean? = null
    override suspend fun getDisplayNameByAddress(address: String): String? = null
    override suspend fun updateNickname(threadId: Long, nickname: String?) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun count(): Int = 0
}
