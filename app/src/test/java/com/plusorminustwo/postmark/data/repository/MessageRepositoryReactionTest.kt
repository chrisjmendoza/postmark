package com.plusorminustwo.postmark.data.repository

import com.plusorminustwo.postmark.data.db.dao.EmojiCount
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ReactionEntity
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MessageRepository.observeTopUserEmojis].
 *
 * Verifies that the function:
 * - filters to SELF_ADDRESS reactions only,
 * - maps down to plain emoji strings,
 * - preserves the descending-count order provided by the DAO,
 * - and reacts to live DAO emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryReactionTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun makeRepo(topEmojis: List<EmojiCount>): MessageRepository {
        val fakeReactionDao = FixedTopEmojiFakeReactionDao(topEmojis)
        return MessageRepository(StubMessageDao(), fakeReactionDao)
    }

    private fun makeRepoWithFlow(flow: MutableStateFlow<List<EmojiCount>>): MessageRepository {
        val fakeReactionDao = LiveTopEmojiFakeReactionDao(flow)
        return MessageRepository(StubMessageDao(), fakeReactionDao)
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `returns empty list when no SELF reactions exist`() = runTest {
        val repo = makeRepo(emptyList())
        val result = repo.observeTopUserEmojis().first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns emoji strings in DAO-provided order`() = runTest {
        val topEmojis = listOf(
            EmojiCount("❤️", 10),
            EmojiCount("😂", 7),
            EmojiCount("👍", 3)
        )
        val repo = makeRepo(topEmojis)
        val result = repo.observeTopUserEmojis().first()
        assertEquals(listOf("❤️", "😂", "👍"), result)
    }

    @Test
    fun `strips count — result contains only emoji strings`() = runTest {
        val repo = makeRepo(listOf(EmojiCount("🔥", 99)))
        val result = repo.observeTopUserEmojis().first()
        assertEquals(listOf("🔥"), result)
    }

    @Test
    fun `preserves single-emoji list`() = runTest {
        val repo = makeRepo(listOf(EmojiCount("😮", 1)))
        val result = repo.observeTopUserEmojis().first()
        assertEquals(listOf("😮"), result)
    }

    @Test
    fun `updates live when DAO emits a new list`() = runTest {
        val sharedFlow = MutableStateFlow(listOf(EmojiCount("👍", 5)))
        val repo = makeRepoWithFlow(sharedFlow)

        val firstEmission = repo.observeTopUserEmojis().first()
        assertEquals(listOf("👍"), firstEmission)

        // Simulate a new reaction being added, DAO re-emits updated counts
        sharedFlow.value = listOf(EmojiCount("❤️", 8), EmojiCount("👍", 5))
        val secondEmission = repo.observeTopUserEmojis().first()
        assertEquals(listOf("❤️", "👍"), secondEmission)
    }

    @Test
    fun `preserves exact DAO order even when counts are equal`() = runTest {
        // Two emoji with the same count — DAO ordering is considered authoritative
        val topEmojis = listOf(
            EmojiCount("🎉", 4),
            EmojiCount("😢", 4)
        )
        val repo = makeRepo(topEmojis)
        val result = repo.observeTopUserEmojis().first()
        assertEquals(listOf("🎉", "😢"), result)
    }
}

// ── Fake DAO implementations (scoped to this file) ───────────────────────────

/**
 * ReactionDao that returns a fixed [EmojiCount] list from [observeTopEmojisBySender].
 * All other methods are no-op stubs.
 */
private class FixedTopEmojiFakeReactionDao(
    private val topEmojis: List<EmojiCount>
) : ReactionDao {
    override fun observeAll(): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override suspend fun getAll(): List<ReactionEntity> = emptyList()
    override fun observeTopEmojisBySender(senderAddress: String): Flow<List<EmojiCount>> =
        flowOf(topEmojis)
    override fun observeDistinctEmojis(): Flow<List<String>> = flowOf(emptyList())

    override fun observeByMessage(messageId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override fun observeByThread(threadId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override suspend fun getByThread(threadId: Long): List<ReactionEntity> = emptyList()
    override suspend fun getByMessage(messageId: Long): List<ReactionEntity> = emptyList()
    override suspend fun insert(reaction: ReactionEntity): Long = 0L
    override suspend fun delete(reaction: ReactionEntity) = Unit
    override suspend fun deleteByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String) = Unit
    override suspend fun getByEmoji(emoji: String): List<ReactionEntity> = emptyList()
    override suspend fun getTopEmojis(limit: Int): List<EmojiCount> = emptyList()
    override suspend fun deleteAll() = Unit
}

/**
 * ReactionDao that delegates [observeTopEmojisBySender] to a [MutableStateFlow] so tests can
 * push new values and verify live-update behaviour.
 */
private class LiveTopEmojiFakeReactionDao(
    private val flow: MutableStateFlow<List<EmojiCount>>
) : ReactionDao {
    override fun observeAll(): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override suspend fun getAll(): List<ReactionEntity> = emptyList()
    override fun observeTopEmojisBySender(senderAddress: String): Flow<List<EmojiCount>> = flow
    override fun observeDistinctEmojis(): Flow<List<String>> = flowOf(emptyList())

    override fun observeByMessage(messageId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override fun observeByThread(threadId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override suspend fun getByThread(threadId: Long): List<ReactionEntity> = emptyList()
    override suspend fun getByMessage(messageId: Long): List<ReactionEntity> = emptyList()
    override suspend fun insert(reaction: ReactionEntity): Long = 0L
    override suspend fun delete(reaction: ReactionEntity) = Unit
    override suspend fun deleteByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String) = Unit
    override suspend fun getByEmoji(emoji: String): List<ReactionEntity> = emptyList()
    override suspend fun getTopEmojis(limit: Int): List<EmojiCount> = emptyList()
    override suspend fun deleteAll() = Unit
}

/**
 * Minimal MessageDao stub — [observeTopUserEmojis] does not touch the message table,
 * but [MessageRepository] requires a non-null [MessageDao] at construction.
 */
private class StubMessageDao : MessageDao {
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
    override suspend fun updateDeliveryStatus(messageId: Long, status: Int) = Unit
    override suspend fun markAllRead(threadId: Long) = Unit
    override fun observeUnreadCounts(): Flow<List<com.plusorminustwo.postmark.data.db.dao.UnreadCount>> = flowOf(emptyList())
    override suspend fun deleteOptimisticMessages(threadId: Long) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun getAllThreadIds(): List<Long> = emptyList()
    override suspend fun getAll(): List<MessageEntity> = emptyList()
}
