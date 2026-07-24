package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.db.dao.EmojiCount
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.dao.UnreadCount
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ReactionEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.reaction.AndroidReactionParser
import com.plusorminustwo.postmark.data.reaction.AppleReactionParser
import com.plusorminustwo.postmark.data.reaction.ReactionFallbackParser
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.RESTORED_ID_OFFSET
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [EmptyMmsBodyRepair] — the self-healing pass for MMS rows that
 * imported with no readable content (file-backed text parts / parts written after the
 * row was observed; see the class doc). The key behavior: a recovered body that turns
 * out to be a reaction fallback must resolve into a Reaction and lose its bubble in
 * the same pass, never surface as a late text bubble.
 *
 * Uses real parsers, resolver, and repositories over in-memory fake DAOs (no mocking
 * libraries, per project convention).
 */
class EmptyMmsBodyRepairTest {

    private lateinit var messageDao: RepairInMemoryMessageDao
    private lateinit var reactionDao: RepairInMemoryReactionDao
    private lateinit var threadDao: RepairRecordingThreadDao
    private lateinit var repair: EmptyMmsBodyRepair

    @Before
    fun setUp() {
        messageDao = RepairInMemoryMessageDao()
        reactionDao = RepairInMemoryReactionDao()
        threadDao = RepairRecordingThreadDao()
        val messageRepository = MessageRepository(messageDao, reactionDao)
        val threadRepository = ThreadRepository(threadDao)
        val parser = ReactionFallbackParser(
            AndroidReactionParser(),
            AppleReactionParser(patternsProvider = { emptyList() })
        )
        repair = EmptyMmsBodyRepair(
            messageRepository,
            threadRepository,
            ReactionResolver(messageRepository, threadRepository, parser)
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun msg(
        id: Long,
        body: String,
        timestamp: Long,
        isMms: Boolean = true,
        isSent: Boolean = false,
        attachmentUri: String? = null,
        mimeType: String? = null,
        address: String = "+15551234567",
        threadId: Long = 1L
    ) = MessageEntity(
        id = id, threadId = threadId, address = address, body = body,
        timestamp = timestamp, isSent = isSent, isMms = isMms,
        attachmentUri = attachmentUri, mimeType = mimeType
    )

    private val emptyRowId = MMS_ID_OFFSET + 77

    // ── recovery paths ───────────────────────────────────────────────────────

    @Test
    fun `recovered reaction fallback resolves into a reaction and removes its bubble`() = runTest {
        // The July 2026 report: a friend's ❤️ to an image+caption MMS arrived as an
        // RCS-archival row whose text part was unreadable at import → empty bubble.
        val originalId = MMS_ID_OFFSET + 5
        messageDao.seed(
            msg(originalId, "How our chat looks like in my texting app 😎", timestamp = 1_000, isSent = true),
            msg(emptyRowId, "", timestamp = 2_000)
        )

        val result = repair.repair(rereadParts = { rawId ->
            if (rawId == 77L) MmsParsedResult("❤️ to \"How our chat looks like in my texting app 😎\"", emptyList())
            else null
        })

        assertEquals(EmptyMmsBodyRepair.Result(repaired = 1, stillEmpty = 0), result)
        val reaction = reactionDao.rows.single()
        assertEquals(originalId, reaction.messageId)
        assertEquals("❤️", reaction.emoji)
        assertEquals("+15551234567", reaction.senderAddress)
        // The fallback bubble is gone; the original remains.
        assertFalse(messageDao.rows.containsKey(emptyRowId))
        assertTrue(messageDao.rows.containsKey(originalId))
    }

    @Test
    fun `recovered caption and attachments update the row and thread preview`() = runTest {
        messageDao.seed(
            msg(MMS_ID_OFFSET + 5, "earlier message", timestamp = 1_000),
            msg(emptyRowId, "", timestamp = 2_000)
        )

        val attachment = MessageAttachment("content://mms/part/901", "image/jpeg")
        val result = repair.repair(rereadParts = { MmsParsedResult("Look at this", listOf(attachment)) })

        assertEquals(EmptyMmsBodyRepair.Result(repaired = 1, stillEmpty = 0), result)
        val row = messageDao.rows.getValue(emptyRowId)
        assertEquals("Look at this", row.body)
        assertEquals("content://mms/part/901", row.attachmentUri)
        // The repaired row is the thread's latest — the stale preview is refreshed.
        assertEquals(1L to "Look at this", threadDao.lastPreviewUpdate)
        assertEquals(1L to 2_000L, threadDao.lastMessageAtUpdate)
    }

    // ── still-unreadable paths ───────────────────────────────────────────────

    @Test
    fun `unreadable parts leave the row untouched for the next pass`() = runTest {
        messageDao.seed(msg(emptyRowId, "", timestamp = 2_000))

        val result = repair.repair(rereadParts = { null })

        assertEquals(EmptyMmsBodyRepair.Result(repaired = 0, stillEmpty = 1), result)
        assertEquals("", messageDao.rows.getValue(emptyRowId).body)
        assertTrue(reactionDao.rows.isEmpty())
    }

    @Test
    fun `content-free reread counts as still empty`() = runTest {
        messageDao.seed(msg(emptyRowId, "", timestamp = 2_000))

        val result = repair.repair(rereadParts = { MmsParsedResult("", emptyList()) })

        assertEquals(EmptyMmsBodyRepair.Result(repaired = 0, stillEmpty = 1), result)
    }

    // ── candidate selection ──────────────────────────────────────────────────

    @Test
    fun `only provider-backed empty mms rows are candidates`() = runTest {
        messageDao.seed(
            msg(42, "", timestamp = 1_000, isMms = false),                          // empty SMS
            msg(RESTORED_ID_OFFSET + 9, "", timestamp = 2_000),                      // restored row
            msg(-5, "", timestamp = 3_000),                                          // optimistic row
            msg(MMS_ID_OFFSET + 8, "has a body", timestamp = 4_000),                 // non-empty
            msg(MMS_ID_OFFSET + 9, "", timestamp = 5_000,
                attachmentUri = "content://mms/part/1", mimeType = "image/jpeg")     // has attachment
        )
        var rereadCalls = 0

        val result = repair.repair(rereadParts = { rereadCalls++; null })

        assertEquals(EmptyMmsBodyRepair.Result(repaired = 0, stillEmpty = 0), result)
        assertEquals(0, rereadCalls)
    }
}

// ── Fakes (scoped to this file, per project convention — no mocking libraries) ──

/** MessageDao with real semantics for the repair-path methods; the rest are stubs. */
private class RepairInMemoryMessageDao : MessageDao {
    val rows = linkedMapOf<Long, MessageEntity>()

    fun seed(vararg messages: MessageEntity) = messages.forEach { rows[it.id] = it }

    override suspend fun getEmptyMmsRows(limit: Int): List<MessageEntity> =
        rows.values.filter {
            it.isMms && it.body.isEmpty() && it.attachmentUri == null && it.attachmentsJson == null &&
                it.id > 0 && it.id < RESTORED_ID_OFFSET
        }.sortedByDescending { it.timestamp }.take(limit)

    override suspend fun updateBody(messageId: Long, body: String) {
        rows[messageId]?.let { rows[messageId] = it.copy(body = body) }
    }

    override suspend fun updateAttachments(messageId: Long, attachmentsJson: String?, firstUri: String?, firstMime: String?) {
        rows[messageId]?.let {
            rows[messageId] = it.copy(attachmentsJson = attachmentsJson, attachmentUri = firstUri, mimeType = firstMime)
        }
    }

    override suspend fun getByThread(threadId: Long): List<MessageEntity> =
        rows.values.filter { it.threadId == threadId }.sortedBy { it.timestamp }
    override suspend fun getAllThreadIds(): List<Long> = rows.values.map { it.threadId }.distinct()
    override suspend fun deleteById(messageId: Long) { rows.remove(messageId) }
    override suspend fun deleteByIds(ids: List<Long>) { ids.forEach { rows.remove(it) } }
    override suspend fun getLatestForThread(threadId: Long): MessageEntity? =
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
    override suspend fun updateDeliveryStatus(messageId: Long, status: Int) = Unit
    override suspend fun updateDeliveryStatusWithTimestamp(messageId: Long, status: Int, deliveredAt: Long) = Unit
    override suspend fun updateThreadId(messageId: Long, threadId: Long) = Unit
    override suspend fun deleteOptimisticMessages(threadId: Long, isMms: Boolean) = Unit
    override suspend fun getOptimisticSentDeliveryStatus(threadId: Long, isMms: Boolean): Int? = null
    override suspend fun getOptimisticSentId(threadId: Long, isMms: Boolean): Long? = null
    override suspend fun getOptimisticSentMms(threadId: Long): List<MessageEntity> = emptyList()
    override suspend fun deleteAll() = Unit
    override suspend fun getAll(): List<MessageEntity> = rows.values.toList()
    override suspend fun getQueuedMessages(): List<MessageEntity> = emptyList()
    override suspend fun hasQueuedInThread(threadId: Long): Boolean = false
    override suspend fun getMaxId(): Long? = null
    override suspend fun getMaxMmsId(): Long? = null
    override suspend fun getMinMmsId(): Long? = null
    override suspend fun hasAnyMessages(): Boolean = rows.isNotEmpty()
    override suspend fun getMaxRestoredId(): Long? = null
    override suspend fun markAllRead(threadId: Long) = Unit
    override suspend fun markLatestUnread(threadId: Long) = Unit
    override fun observeUnreadCounts(): Flow<List<UnreadCount>> = flowOf(emptyList())
    override fun observeMediaMessages(threadId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
    override suspend fun getAllWithAttachments(): List<MessageEntity> = emptyList()
    override suspend fun getMessageCountsByThread(): List<com.plusorminustwo.postmark.data.db.dao.ThreadMessageCount> = emptyList()
    override suspend fun updateStarred(messageId: Long, isStarred: Boolean) = Unit
    override fun observeStarredMedia(): Flow<List<MessageEntity>> = flowOf(emptyList())
    override suspend fun updatePinned(messageId: Long, isPinned: Boolean) = Unit
    override fun observePinnedByThread(threadId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
    override suspend fun updateRemindAt(messageId: Long, remindAt: Long?) = Unit
    override fun observeFlaggedByThread(threadId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
}

/** ReactionDao backed by an in-memory list with real insert / count / delete semantics. */
private class RepairInMemoryReactionDao : ReactionDao {
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
    override suspend fun getByMessageIds(messageIds: List<Long>): List<ReactionEntity> = emptyList()
    override suspend fun getTopEmojis(limit: Int): List<EmojiCount> = emptyList()
    override suspend fun deleteAll() { rows.clear() }
}

/** ThreadDao that records preview repairs; everything else is a no-op stub. */
private class RepairRecordingThreadDao : ThreadDao {
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
    override suspend fun getAll(): List<ThreadEntity> = emptyList()
    override suspend fun updateDisplayName(threadId: Long, displayName: String) {}
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
    override fun observeNonSpam(): Flow<List<ThreadEntity>> = observeAll()
    override fun observeSpam(): Flow<List<ThreadEntity>> = observeAll()
    override suspend fun updateSpam(threadId: Long, isSpam: Boolean) = Unit
    override suspend fun markSpam(ids: List<Long>) = Unit
    override suspend fun getThreadsForBackup(): List<ThreadEntity> = emptyList()
    override suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity> = emptyList()
    override suspend fun getThreadsWithParticipants(): List<ThreadEntity> = emptyList()
    override suspend fun updateRoster(threadId: Long, participantsJson: String?, displayName: String) {}
    override suspend fun updateNickname(threadId: Long, nickname: String?) = Unit
    override suspend fun updateAccentColor(threadId: Long, argb: Int?) = Unit
    override suspend fun updateChatBackground(threadId: Long, backgroundId: String?) = Unit
    override suspend fun updateSentColor(threadId: Long, argb: Int?) = Unit
    override suspend fun countByChatBackground(id: String): Int = 0
    override suspend fun deleteAll() = Unit
    override suspend fun count(): Int = 0
}
