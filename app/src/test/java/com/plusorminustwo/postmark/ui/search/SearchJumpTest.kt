package com.plusorminustwo.postmark.ui.search

import com.plusorminustwo.postmark.data.db.dao.SearchDao
import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.repository.SearchRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
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
class SearchJumpTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val fakeThread = Thread(
        id = 10L,
        displayName = "Alice",
        address = "+15550001111",
        lastMessageAt = 0L,
        lastMessagePreview = "",
        backupPolicy = BackupPolicy.GLOBAL
    )

    private val fakeMessageEntity = MessageEntity(
        id = 99L,
        threadId = 10L,
        address = "+15550001111",
        body = "Hello world",
        timestamp = 1_000_000L,
        isSent = false
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `search result has correct threadId and messageId`() = runTest {
        val viewModel = buildViewModel(messages = listOf(fakeMessageEntity))

        viewModel.onQueryChange("Hello")
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        val result = state.results.first()

        assertEquals(fakeMessageEntity.threadId, result.threadId)
        assertEquals(fakeMessageEntity.id, result.id)
    }

    @Test
    fun `thread filter sets threadId in search filters`() = runTest {
        val viewModel = buildViewModel(threads = listOf(
            ThreadEntity(fakeThread.id, fakeThread.displayName, fakeThread.address, 0L)
        ))

        viewModel.setThreadFilter(fakeThread)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals(fakeThread.id, state.filters.threadId)
        assertEquals(fakeThread, state.selectedThread)
    }

    @Test
    fun `clearing thread filter sets threadId to null`() = runTest {
        val viewModel = buildViewModel()

        viewModel.setThreadFilter(fakeThread)
        viewModel.setThreadFilter(null)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertNull(state.filters.threadId)
        assertNull(state.selectedThread)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildViewModel(
        messages: List<MessageEntity> = emptyList(),
        threads: List<ThreadEntity> = emptyList()
    ): SearchViewModel = SearchViewModel(
        searchRepository = SearchRepository(FakeSearchDao(messages)),
        threadRepository = ThreadRepository(FakeThreadDao(threads))
    )

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeSearchDao(private val results: List<MessageEntity>) : SearchDao {
        override suspend fun searchMessagesFiltered(
            query: String, threadId: Long, isSentInt: Int, startMs: Long, limit: Int, offset: Int
        ) = results.filter {
            (threadId == -1L || it.threadId == threadId) &&
            (isSentInt == -1 || it.isSent == (isSentInt == 1)) &&
            (startMs == -1L || it.timestamp >= startMs)
        }

        override suspend fun searchMessagesFilteredWithReaction(
            query: String, threadId: Long, isSentInt: Int, startMs: Long,
            reactionEmoji: String, limit: Int, offset: Int
        ) = emptyList<MessageEntity>()
    }

    private class FakeThreadDao(private val threads: List<ThreadEntity> = emptyList()) : ThreadDao {
        override fun observeAll(): Flow<List<ThreadEntity>> = flowOf(threads)
        override suspend fun getById(threadId: Long): ThreadEntity? = threads.find { it.id == threadId }
        override fun observeById(threadId: Long): Flow<ThreadEntity?> = flowOf(threads.find { it.id == threadId })
        override suspend fun insert(thread: ThreadEntity) {}
        override suspend fun insertAll(threads: List<ThreadEntity>) {}
        override suspend fun update(thread: ThreadEntity) {}
        override suspend fun delete(thread: ThreadEntity) {}
        override suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) {}
        override suspend fun getThreadsForBackup(): List<ThreadEntity> = emptyList()
        override suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity> = emptyList()
        override suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) {}
        override suspend fun updateLastMessagePreview(threadId: Long, preview: String) {}
        override suspend fun updateMuted(threadId: Long, isMuted: Boolean) {}
        override suspend fun deleteAll() {}
    }
}

