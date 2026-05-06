package com.plusorminustwo.postmark.ui.search

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
import com.plusorminustwo.postmark.domain.model.toBoundsMs
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
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
class SearchDateRangeTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    // ── Pure function tests ────────────────────────────────────────────────

    @Test
    fun `ALL_TIME returns null bounds`() {
        val (start, end) = SearchDateRange.ALL_TIME.toBoundsMs(nowMs = 1_000_000L)
        assertNull(start)
        assertNull(end)
    }

    @Test
    fun `TODAY starts at local midnight`() {
        val nowMs = System.currentTimeMillis()
        val (start, end) = SearchDateRange.TODAY.toBoundsMs(nowMs)
        assertNull(end)
        assertNotNull(start)

        val cal = Calendar.getInstance()
        cal.timeInMillis = start!!
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
        // start is today's midnight — must be at or before now
        assertTrue(start <= nowMs)
    }

    @Test
    fun `LAST_7_DAYS starts at now minus 7 days`() {
        val now = 1_000_000_000L
        val (start, end) = SearchDateRange.LAST_7_DAYS.toBoundsMs(now)
        assertNull(end)
        assertEquals(now - 7L * 24L * 3_600_000L, start)
    }

    @Test
    fun `LAST_30_DAYS starts at now minus 30 days`() {
        val now = 1_000_000_000L
        val (start, end) = SearchDateRange.LAST_30_DAYS.toBoundsMs(now)
        assertNull(end)
        assertEquals(now - 30L * 24L * 3_600_000L, start)
    }

    // ── ViewModel integration tests ────────────────────────────────────────

    @Test
    fun `search excludes messages outside selected range`() = runTest {
        val nowMs = System.currentTimeMillis()
        // A message from 10 days ago — outside LAST_7_DAYS
        val oldMessage = msg(id = 1, timestamp = nowMs - 10L * 24L * 3_600_000L)
        // A message from 1 hour ago — inside LAST_7_DAYS
        val recentMessage = msg(id = 2, timestamp = nowMs - 3_600_000L)

        val dao = SpySearchDao(listOf(oldMessage, recentMessage))
        val viewModel = buildViewModel(dao)
        viewModel.setDateRangeFilter(SearchDateRange.LAST_7_DAYS)
        viewModel.onQueryChange("hello")
        advanceUntilIdle()

        // Verify startMs was passed — it should exclude the old message
        val startMs = dao.capturedStartMs
        assertNotNull(startMs)
        assertTrue(oldMessage.timestamp < startMs!!)
        assertTrue(recentMessage.timestamp >= startMs)
    }

    @Test
    fun `search includes messages inside selected range`() = runTest {
        val nowMs = System.currentTimeMillis()
        // Compute today's midnight the same way SearchDateRange.TODAY does, then place
        // the message 1 second AFTER midnight — always within today regardless of timezone.
        val todayMidnightMs = SearchDateRange.TODAY.toBoundsMs(nowMs).first!!
        val recentMessage = msg(id = 1, timestamp = todayMidnightMs + 1_000L)

        val dao = SpySearchDao(listOf(recentMessage))
        val viewModel = buildViewModel(dao)
        viewModel.setDateRangeFilter(SearchDateRange.TODAY)
        viewModel.onQueryChange("hello")
        advanceUntilIdle()

        val startMs = dao.capturedStartMs
        assertNotNull(startMs)
        // Message placed 1 second past midnight is always within today
        assertTrue(recentMessage.timestamp >= startMs!!)
    }

    @Test
    fun `ALL_TIME passes no startMs filter`() = runTest {
        val dao = SpySearchDao(listOf(msg(id = 1, timestamp = 100L)))
        val viewModel = buildViewModel(dao)
        // ALL_TIME is the default — no filter change needed
        viewModel.onQueryChange("hello")
        advanceUntilIdle()

        // ALL_TIME should result in sentinel -1L being passed (no filter)
        assertEquals(-1L, dao.capturedStartMs)
    }

    @Test
    fun `thread filter and date range filter stack together`() = runTest {
        val viewModel = buildViewModel()
        val thread = Thread(1L, "Alice", "+1", 0L)

        viewModel.setThreadFilter(thread)
        viewModel.setDateRangeFilter(SearchDateRange.LAST_7_DAYS)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals(1L, state.filters.threadId)
        assertEquals(SearchDateRange.LAST_7_DAYS, state.filters.dateRange)
    }

    @Test
    fun `date range resets to ALL_TIME when ALL_TIME is set`() = runTest {
        val viewModel = buildViewModel()

        viewModel.setDateRangeFilter(SearchDateRange.LAST_30_DAYS)
        advanceUntilIdle()
        assertEquals(SearchDateRange.LAST_30_DAYS, viewModel.uiState.first().filters.dateRange)

        viewModel.setDateRangeFilter(SearchDateRange.ALL_TIME)
        advanceUntilIdle()
        assertEquals(SearchDateRange.ALL_TIME, viewModel.uiState.first().filters.dateRange)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildViewModel(
        dao: SearchDao = SpySearchDao(emptyList())
    ): SearchViewModel = SearchViewModel(
        searchRepository = SearchRepository(dao, FakeReactionDao()),
        threadRepository = ThreadRepository(FakeThreadDao())
    )

    private fun msg(id: Long, timestamp: Long = 1_000L) = MessageEntity(
        id = id, threadId = 1L, address = "+1", body = "hello", timestamp = timestamp, isSent = false
    )

    // ── Fakes ─────────────────────────────────────────────────────────────────

    /** Captures search parameters for assertion. */
    private class SpySearchDao(private val results: List<MessageEntity>) : SearchDao {
        var capturedStartMs: Long? = null
        var capturedThreadId: Long? = null

        override suspend fun searchMessagesFiltered(
            query: String, threadId: Long, isSentInt: Int, startMs: Long, isMmsInt: Int, limit: Int, offset: Int
        ): List<MessageEntity> {
            capturedStartMs = startMs
            capturedThreadId = threadId
            return results.filter {
                (threadId == -1L || it.threadId == threadId) &&
                (startMs == -1L || it.timestamp >= startMs)
            }
        }

        override suspend fun searchMessagesFilteredWithReaction(
            query: String, threadId: Long, isSentInt: Int, startMs: Long, isMmsInt: Int,
            reactionEmoji: String, limit: Int, offset: Int
        ) = emptyList<MessageEntity>()

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
