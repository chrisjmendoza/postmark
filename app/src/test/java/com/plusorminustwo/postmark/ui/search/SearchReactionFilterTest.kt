package com.plusorminustwo.postmark.ui.search

import androidx.lifecycle.SavedStateHandle
import com.plusorminustwo.postmark.data.db.dao.EmojiCount
import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.dao.SearchDao
import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ReactionEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.repository.SearchRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.SearchDateRange
import com.plusorminustwo.postmark.domain.model.Thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchReactionFilterTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val msgWithHeart = MessageEntity(id = 1L, threadId = 10L, address = "+1", body = "hello", timestamp = 1000L, isSent = false)
    private val msgWithThumb = MessageEntity(id = 2L, threadId = 10L, address = "+1", body = "world", timestamp = 2000L, isSent = false)
    private val msgNoReaction = MessageEntity(id = 3L, threadId = 10L, address = "+1", body = "foo", timestamp = 3000L, isSent = false)

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `filtering by emoji returns messages with that reaction`() = runTest {
        val dao = FakeSearchDao(
            allMessages = listOf(msgWithHeart, msgWithThumb, msgNoReaction),
            reactingMessages = mapOf("❤️" to listOf(msgWithHeart), "👍" to listOf(msgWithThumb))
        )
        val viewModel = buildViewModel(dao)

        viewModel.setReactionFilter("❤️")
        viewModel.onQueryChange("hello")
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals("❤️", state.filters.reactionEmoji)
        assertEquals(listOf(msgWithHeart.id), state.results.map { it.id })
    }

    @Test
    fun `messages without that reaction are excluded`() = runTest {
        val dao = FakeSearchDao(
            allMessages = listOf(msgWithHeart, msgWithThumb, msgNoReaction),
            reactingMessages = mapOf("❤️" to listOf(msgWithHeart))
        )
        val viewModel = buildViewModel(dao)

        viewModel.setReactionFilter("❤️")
        viewModel.onQueryChange("hello")
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertTrue(state.results.none { it.id == msgWithThumb.id })
        assertTrue(state.results.none { it.id == msgNoReaction.id })
    }

    @Test
    fun `clearing reaction filter restores normal results`() = runTest {
        val dao = FakeSearchDao(
            allMessages = listOf(msgWithHeart, msgWithThumb),
            reactingMessages = mapOf("❤️" to listOf(msgWithHeart))
        )
        val viewModel = buildViewModel(dao)
        viewModel.onQueryChange("hello")

        viewModel.setReactionFilter("❤️")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.first().results.size)

        viewModel.setReactionFilter(null)
        advanceUntilIdle()
        assertNull(viewModel.uiState.first().filters.reactionEmoji)
        // Without reaction filter, all messages are returned
        assertEquals(2, viewModel.uiState.first().results.size)
    }

    @Test
    fun `reaction filter stacks with thread filter`() = runTest {
        val msgThread2 = MessageEntity(id = 4L, threadId = 20L, address = "+2", body = "hello", timestamp = 4000L, isSent = false)
        val dao = FakeSearchDao(
            allMessages = listOf(msgWithHeart, msgThread2),
            reactingMessages = mapOf("❤️" to listOf(msgWithHeart, msgThread2))
        )
        val viewModel = buildViewModel(dao)
        val thread1 = Thread(10L, "Alice", "+1", 0L)

        viewModel.setThreadFilter(thread1)
        viewModel.setReactionFilter("❤️")
        viewModel.onQueryChange("hello")
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals(10L, state.filters.threadId)
        assertEquals("❤️", state.filters.reactionEmoji)
        // Only messages in thread 10 with ❤️ reaction
        assertTrue(state.results.all { it.threadId == 10L })
    }

    @Test
    fun `reaction filter stacks with date range filter`() = runTest {
        val oldMsg = MessageEntity(id = 5L, threadId = 10L, address = "+1", body = "hello", timestamp = 100L, isSent = false)
        val newMsg = MessageEntity(id = 6L, threadId = 10L, address = "+1", body = "hello", timestamp = System.currentTimeMillis() - 3600_000L, isSent = false)
        val dao = FakeSearchDao(
            allMessages = listOf(oldMsg, newMsg),
            reactingMessages = mapOf("❤️" to listOf(oldMsg, newMsg))
        )
        val viewModel = buildViewModel(dao)

        viewModel.setReactionFilter("❤️")
        viewModel.setDateRangeFilter(SearchDateRange.TODAY)
        viewModel.onQueryChange("hello")
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals("❤️", state.filters.reactionEmoji)
        assertEquals(SearchDateRange.TODAY, state.filters.dateRange)
        // Only recent message (within today) should appear
        assertTrue(state.results.all { it.timestamp > 1000L })
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildViewModel(dao: SearchDao) = SearchViewModel(
        savedStateHandle = SavedStateHandle(),
        searchRepository = SearchRepository(dao, FakeReactionDao()),
        threadRepository = ThreadRepository(FakeThreadDao())
    )

    /**
     * Fake DAO that supports reaction-based filtering.
     * [reactingMessages] maps emoji → messages that have that reaction.
     */
    private class FakeSearchDao(
        private val allMessages: List<MessageEntity>,
        private val reactingMessages: Map<String, List<MessageEntity>> = emptyMap()
    ) : SearchDao {
        override suspend fun searchMessagesFiltered(
            query: String, threadId: Long, isSentInt: Int, startMs: Long, isMmsInt: Int, limit: Int, offset: Int
        ) = allMessages.filter {
            (threadId == -1L || it.threadId == threadId) &&
            (isSentInt == -1 || it.isSent == (isSentInt == 1)) &&
            (startMs == -1L || it.timestamp >= startMs)
        }

        override suspend fun searchMessagesFilteredWithReaction(
            query: String, threadId: Long, isSentInt: Int, startMs: Long, isMmsInt: Int,
            reactionEmoji: String, limit: Int, offset: Int
        ): List<MessageEntity> {
            val withReaction = reactingMessages[reactionEmoji] ?: emptyList()
            return withReaction.filter {
                (threadId == -1L || it.threadId == threadId) &&
                (isSentInt == -1 || it.isSent == (isSentInt == 1)) &&
                (startMs == -1L || it.timestamp >= startMs)
            }
        }

        override suspend fun browseFiltered(
            threadId: Long, isSentInt: Int, startMs: Long, isMmsInt: Int, limit: Int, offset: Int
        ) = emptyList<MessageEntity>()
    }

    private class FakeThreadDao : ThreadDao {
        override fun observeAll(): Flow<List<ThreadEntity>> = flowOf(emptyList())
        override fun observeById(id: Long): Flow<ThreadEntity?> = flowOf(null)
        override suspend fun getById(id: Long): ThreadEntity? = null
        override suspend fun insert(t: ThreadEntity) {}
        override suspend fun insertAll(ts: List<ThreadEntity>) {}
        override suspend fun insertIgnore(t: ThreadEntity) {}
        override suspend fun insertAllIgnore(ts: List<ThreadEntity>) {}
        override suspend fun update(t: ThreadEntity) {}
        override suspend fun delete(t: ThreadEntity) {}
        override suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) {}
        override suspend fun getThreadsForBackup(): List<ThreadEntity> = emptyList()
        override suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity> = emptyList()
        override suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) {}
        override suspend fun updateLastMessagePreview(threadId: Long, preview: String) {}
        override suspend fun isMutedByAddress(address: String): Boolean? = null
        override suspend fun isNotificationsEnabledByAddress(address: String): Boolean? = null
        override suspend fun updateNotificationsEnabled(threadId: Long, enabled: Boolean) {}
        override suspend fun deleteAll() {}
        override suspend fun count(): Int = 0
        override suspend fun updateMuted(threadId: Long, isMuted: Boolean) {}
        override suspend fun updatePinned(threadId: Long, isPinned: Boolean) {}
    }

    private class FakeReactionDao : ReactionDao {
        override fun observeAll(): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<ReactionEntity> = emptyList()
        override fun observeByMessage(messageId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override suspend fun getByMessage(messageId: Long): List<ReactionEntity> = emptyList()
        override fun observeByThread(threadId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override suspend fun getByThread(threadId: Long): List<ReactionEntity> = emptyList()
        override fun observeTopEmojisBySender(senderAddress: String): Flow<List<EmojiCount>> = flowOf(emptyList())
        override fun observeDistinctEmojis(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun insert(reaction: ReactionEntity): Long = 0L
        override suspend fun delete(reaction: ReactionEntity) {}
        override suspend fun deleteByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String) {}
        override suspend fun getByEmoji(emoji: String): List<ReactionEntity> = emptyList()
        override suspend fun getTopEmojis(limit: Int): List<EmojiCount> = emptyList()
        override suspend fun deleteAll() {}
        override suspend fun countByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String): Int = 0
    }
}
