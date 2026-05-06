package com.plusorminustwo.postmark.data.repository

import com.plusorminustwo.postmark.data.db.dao.EmojiCount
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ReactionEntity
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MessageRepository.updateDeliveryStatus].
 *
 * Verifies that the repository correctly forwards status updates to the DAO —
 * the contract used by [ThreadViewModel.retrySend] to reset a FAILED message to PENDING.
 */
class FailedSendRetryTest {

    private lateinit var fakeMessageDao: RecordingMessageDao
    private lateinit var repository: MessageRepository

    @Before
    fun setUp() {
        fakeMessageDao = RecordingMessageDao()
        repository = MessageRepository(fakeMessageDao, StubReactionDao())
    }

    // ── updateDeliveryStatus routing ─────────────────────────────────────────

    @Test
    fun `updateDeliveryStatus PENDING passes correct args to DAO`() = runTest {
        repository.updateDeliveryStatus(42L, DELIVERY_STATUS_PENDING)
        assertEquals(42L to DELIVERY_STATUS_PENDING, fakeMessageDao.lastStatusUpdate)
    }

    @Test
    fun `updateDeliveryStatus FAILED passes correct args to DAO`() = runTest {
        repository.updateDeliveryStatus(7L, DELIVERY_STATUS_FAILED)
        assertEquals(7L to DELIVERY_STATUS_FAILED, fakeMessageDao.lastStatusUpdate)
    }

    @Test
    fun `updateDeliveryStatus SENT passes correct args to DAO`() = runTest {
        repository.updateDeliveryStatus(99L, DELIVERY_STATUS_SENT)
        assertEquals(99L to DELIVERY_STATUS_SENT, fakeMessageDao.lastStatusUpdate)
    }

    @Test
    fun `updateDeliveryStatus DELIVERED passes correct args to DAO`() = runTest {
        repository.updateDeliveryStatus(1L, DELIVERY_STATUS_DELIVERED)
        assertEquals(1L to DELIVERY_STATUS_DELIVERED, fakeMessageDao.lastStatusUpdate)
    }

    // ── No spurious calls ────────────────────────────────────────────────────

    @Test
    fun `DAO not called before any update`() {
        assertNull(fakeMessageDao.lastStatusUpdate)
    }

    @Test
    fun `second update overwrites first`() = runTest {
        repository.updateDeliveryStatus(1L, DELIVERY_STATUS_SENT)
        repository.updateDeliveryStatus(1L, DELIVERY_STATUS_DELIVERED)
        assertEquals(1L to DELIVERY_STATUS_DELIVERED, fakeMessageDao.lastStatusUpdate)
    }

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class RecordingMessageDao : MessageDao {
        var lastStatusUpdate: Pair<Long, Int>? = null

        override suspend fun updateDeliveryStatus(messageId: Long, status: Int) {
            lastStatusUpdate = messageId to status
        }

        override fun observeByThread(threadId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
        override fun observeMessagesFrom(startMs: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
        override fun observeMessagesFromForThread(threadId: Long, startMs: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
        override fun observeMessagesInRange(startMs: Long, endMs: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
        override fun observeMessagesInRangeForThread(threadId: Long, startMs: Long, endMs: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun getByThread(threadId: Long): List<MessageEntity> = emptyList()
        override suspend fun getById(messageId: Long): MessageEntity? = null
        override suspend fun insert(message: MessageEntity): Long = 0L
        override suspend fun insertAll(messages: List<MessageEntity>) = Unit
        override suspend fun delete(message: MessageEntity) = Unit
        override suspend fun deleteByThread(threadId: Long) = Unit
        override suspend fun countByThread(threadId: Long): Int = 0
        override suspend fun getByThreadAndDateRange(threadId: Long, startMs: Long, endMs: Long): List<MessageEntity> = emptyList()
        override suspend fun getActiveDatesForThread(threadId: Long): List<String> = emptyList()
        override suspend fun getLatestForThread(threadId: Long): MessageEntity? = null
        override suspend fun getLatestNForThread(threadId: Long, n: Int): List<MessageEntity> = emptyList()
        override suspend fun getLatestBeforeForThread(threadId: Long, timestamp: Long): MessageEntity? = null
        override suspend fun deleteOptimisticMessages(threadId: Long) = Unit
        override suspend fun getOptimisticSentDeliveryStatus(threadId: Long): Int? = null
        override suspend fun deleteAll() = Unit
        override suspend fun getAllThreadIds(): List<Long> = emptyList()
        override suspend fun getAll(): List<MessageEntity> = emptyList()
        override suspend fun getMaxId(): Long? = null
        override suspend fun getMaxMmsId(): Long? = null
        override suspend fun getMinMmsId(): Long? = null
        override suspend fun deleteById(messageId: Long) = Unit
        override suspend fun getLatestNonReactionForThread(threadId: Long): MessageEntity? = null
        override suspend fun markAllRead(threadId: Long) = Unit
        override fun observeUnreadCounts(): Flow<List<com.plusorminustwo.postmark.data.db.dao.UnreadCount>> = flowOf(emptyList())
    }

    private class StubReactionDao : ReactionDao {
        override fun observeAll(): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<ReactionEntity> = emptyList()
        override fun observeByMessage(messageId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override fun observeByThread(threadId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override suspend fun getByThread(threadId: Long): List<ReactionEntity> = emptyList()
        override suspend fun getByMessage(messageId: Long): List<ReactionEntity> = emptyList()
        override suspend fun insert(reaction: ReactionEntity): Long = 0L
        override suspend fun delete(reaction: ReactionEntity) = Unit
        override suspend fun deleteByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String) = Unit
        override suspend fun getByEmoji(emoji: String): List<ReactionEntity> = emptyList()
        override suspend fun getTopEmojis(limit: Int): List<EmojiCount> = emptyList()
        override fun observeTopEmojisBySender(senderAddress: String): Flow<List<EmojiCount>> = flowOf(emptyList())
        override fun observeDistinctEmojis(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun deleteAll() = Unit
        override suspend fun countByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String): Int = 0
    }
}
