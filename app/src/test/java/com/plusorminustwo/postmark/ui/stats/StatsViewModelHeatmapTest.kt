package com.plusorminustwo.postmark.ui.stats

import androidx.lifecycle.SavedStateHandle
import com.plusorminustwo.postmark.data.db.dao.GlobalStatsDao
import com.plusorminustwo.postmark.data.db.dao.GlobalCounts
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.dao.ThreadStatsDao
import com.plusorminustwo.postmark.data.db.entity.GlobalStatsEntity
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ReactionEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadStatsEntity
import com.plusorminustwo.postmark.data.sync.StatsUpdater
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Pure JVM state-machine tests for [StatsViewModel] heatmap-related actions.
 *
 * These tests verify the MutableStateFlow mutations directly, without requiring
 * Hilt or a real database. Fake DAO implementations are used so the ViewModel
 * can be constructed without the Android runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelHeatmapTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope relies on Dispatchers.Main; replace with the test dispatcher
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): StatsViewModel {
        val messageDao = FakeMessageDao()
        val threadDao  = FakeThreadDao()
        val statsUpdater = StatsUpdater(messageDao, FakeThreadStatsDao(), FakeGlobalStatsDao(), FakeReactionDao())
        return StatsViewModel(threadDao, messageDao, FakeReactionDao(), statsUpdater, savedStateHandle)
    }

    // ── Default state ─────────────────────────────────────────────────────

    @Test
    fun `default heatmapMonth is current month`() {
        val vm = makeViewModel()
        assertEquals(YearMonth.now(), vm.heatmapMonth.value)
    }

    @Test
    fun `default selectedHeatmapDays is empty`() {
        val vm = makeViewModel()
        assertTrue(vm.selectedHeatmapDays.value.isEmpty())
    }

    @Test
    fun `default directThreadNavigation is false`() {
        val vm = makeViewModel()
        assertFalse(vm.directThreadNavigation.value)
    }

    @Test
    fun `default scope is GLOBAL`() {
        val vm = makeViewModel()
        assertEquals(StatsScope.GLOBAL, vm.selectedScope.value)
    }

    // ── Month navigation ──────────────────────────────────────────────────

    @Test
    fun `setHeatmapMonth updates heatmapMonth`() {
        val vm   = makeViewModel()
        val prev = YearMonth.now().minusMonths(1)
        vm.setHeatmapMonth(prev)
        assertEquals(prev, vm.heatmapMonth.value)
    }

    @Test
    fun `setHeatmapMonth clears selectedHeatmapDays`() {
        val vm = makeViewModel()
        vm.toggleHeatmapDay(LocalDate.now())
        vm.setHeatmapMonth(YearMonth.now().minusMonths(1))
        assertTrue(vm.selectedHeatmapDays.value.isEmpty())
    }

    @Test
    fun `setHeatmapMonth navigating back then forward retains last value`() {
        val vm   = makeViewModel()
        val base = YearMonth.now()
        vm.setHeatmapMonth(base.minusMonths(3))
        vm.setHeatmapMonth(base.minusMonths(2))
        vm.setHeatmapMonth(base.minusMonths(1))
        assertEquals(base.minusMonths(1), vm.heatmapMonth.value)
    }

    // ── Day selection (multi) ─────────────────────────────────────────────

    @Test
    fun `toggleHeatmapDay adds day to selection`() {
        val vm  = makeViewModel()
        val day = LocalDate.now()
        vm.toggleHeatmapDay(day)
        assertTrue(day in vm.selectedHeatmapDays.value)
    }

    @Test
    fun `toggleHeatmapDay removes day when already selected`() {
        val vm  = makeViewModel()
        val day = LocalDate.now()
        vm.toggleHeatmapDay(day)
        vm.toggleHeatmapDay(day)
        assertTrue(vm.selectedHeatmapDays.value.isEmpty())
    }

    @Test
    fun `toggleHeatmapDay can select multiple days independently`() {
        val vm   = makeViewModel()
        val day1 = LocalDate.now()
        val day2 = day1.minusDays(1)
        val day3 = day1.minusDays(2)
        vm.toggleHeatmapDay(day1)
        vm.toggleHeatmapDay(day2)
        vm.toggleHeatmapDay(day3)
        assertEquals(setOf(day1, day2, day3), vm.selectedHeatmapDays.value)
    }

    @Test
    fun `toggleHeatmapDay removing one day leaves others selected`() {
        val vm   = makeViewModel()
        val day1 = LocalDate.now()
        val day2 = day1.minusDays(1)
        vm.toggleHeatmapDay(day1)
        vm.toggleHeatmapDay(day2)
        vm.toggleHeatmapDay(day1)  // deselect day1
        assertEquals(setOf(day2), vm.selectedHeatmapDays.value)
    }

    @Test
    fun `clearHeatmapDays empties the selection`() {
        val vm = makeViewModel()
        vm.toggleHeatmapDay(LocalDate.now())
        vm.toggleHeatmapDay(LocalDate.now().minusDays(1))
        vm.clearHeatmapDays()
        assertTrue(vm.selectedHeatmapDays.value.isEmpty())
    }

    // ── preSelectThread ───────────────────────────────────────────────────

    @Test
    fun `preSelectThread sets scope to PER_THREAD`() {
        val vm = makeViewModel()
        vm.preSelectThread(42L)
        assertEquals(StatsScope.PER_THREAD, vm.selectedScope.value)
    }

    @Test
    fun `preSelectThread sets selectedThreadId`() {
        val vm = makeViewModel()
        vm.preSelectThread(42L)
        assertEquals(42L, vm.selectedThreadId.value)
    }

    @Test
    fun `preSelectThread sets directThreadNavigation to true`() {
        val vm = makeViewModel()
        vm.preSelectThread(42L)
        assertTrue(vm.directThreadNavigation.value)
    }

    // ── setScope ──────────────────────────────────────────────────────────

    @Test
    fun `setScope GLOBAL resets directThreadNavigation to false`() {
        val vm = makeViewModel()
        vm.preSelectThread(42L)
        vm.setScope(StatsScope.GLOBAL)
        assertFalse(vm.directThreadNavigation.value)
    }

    @Test
    fun `setScope GLOBAL clears selectedThreadId`() {
        val vm = makeViewModel()
        vm.preSelectThread(42L)
        vm.setScope(StatsScope.GLOBAL)
        assertNull(vm.selectedThreadId.value)
    }

    @Test
    fun `setScope GLOBAL clears selectedHeatmapDays`() {
        val vm = makeViewModel()
        vm.toggleHeatmapDay(LocalDate.now())
        vm.setScope(StatsScope.GLOBAL)
        assertTrue(vm.selectedHeatmapDays.value.isEmpty())
    }

    @Test
    fun `setScope PER_THREAD does not change directThreadNavigation`() {
        val vm = makeViewModel()
        assertFalse(vm.directThreadNavigation.value)
        vm.setScope(StatsScope.PER_THREAD)
        // directThreadNavigation stays false; only preSelectThread sets it
        assertFalse(vm.directThreadNavigation.value)
    }

    // ── SavedStateHandle threadId nav arg ─────────────────────────────────

    @Test
    fun `init with threadId nav arg pre-selects thread`() {
        val vm = makeViewModel(SavedStateHandle(mapOf("threadId" to 99L)))
        assertEquals(StatsScope.PER_THREAD, vm.selectedScope.value)
        assertEquals(99L, vm.selectedThreadId.value)
        assertTrue(vm.directThreadNavigation.value)
    }

    @Test
    fun `init without threadId nav arg stays in default state`() {
        val vm = makeViewModel(SavedStateHandle())
        assertEquals(StatsScope.GLOBAL, vm.selectedScope.value)
        assertNull(vm.selectedThreadId.value)
        assertFalse(vm.directThreadNavigation.value)
    }

    @Test
    fun `init with sentinel threadId (-1) stays in default state`() {
        val vm = makeViewModel(SavedStateHandle(mapOf("threadId" to -1L)))
        assertEquals(StatsScope.GLOBAL, vm.selectedScope.value)
        assertNull(vm.selectedThreadId.value)
        assertFalse(vm.directThreadNavigation.value)
    }
}

// ── Fake DAO implementations ──────────────────────────────────────────────────

private class FakeMessageDao : MessageDao {
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
    override suspend fun deleteOptimisticMessages(threadId: Long) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun getAllThreadIds(): List<Long> = emptyList()
    override suspend fun getAll(): List<MessageEntity> = emptyList()
}

private class FakeThreadDao : ThreadDao {
    override fun observeAll(): Flow<List<ThreadEntity>> = flowOf(emptyList())
    override fun observeById(threadId: Long): Flow<ThreadEntity?> = flowOf(null)
    override suspend fun getById(threadId: Long): ThreadEntity? = null
    override suspend fun insert(thread: ThreadEntity) = Unit
    override suspend fun insertAll(threads: List<ThreadEntity>) = Unit
    override suspend fun update(thread: ThreadEntity) = Unit
    override suspend fun delete(thread: ThreadEntity) = Unit
    override suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) = Unit
    override suspend fun updateMuted(threadId: Long, isMuted: Boolean) = Unit
    override suspend fun updatePinned(threadId: Long, isPinned: Boolean) = Unit
    override suspend fun getThreadsForBackup(): List<ThreadEntity> = emptyList()
    override suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity> = emptyList()
    override suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) = Unit
    override suspend fun updateLastMessagePreview(threadId: Long, preview: String) = Unit
    override suspend fun deleteAll() = Unit
}

private class FakeThreadStatsDao : ThreadStatsDao {
    override fun observeByThread(threadId: Long): Flow<ThreadStatsEntity?> = flowOf(null)
    override fun observeAll(): Flow<List<ThreadStatsEntity>> = flowOf(emptyList())
    override suspend fun getByThread(threadId: Long): ThreadStatsEntity? = null
    override suspend fun upsert(stats: ThreadStatsEntity) = Unit
    override suspend fun update(stats: ThreadStatsEntity) = Unit
    override suspend fun getGlobalCounts(): GlobalCounts? = null
}

private class FakeGlobalStatsDao : GlobalStatsDao {
    override fun observe(): Flow<GlobalStatsEntity?> = flowOf(null)
    override suspend fun get(): GlobalStatsEntity? = null
    override suspend fun upsert(stats: GlobalStatsEntity) = Unit
}

private class FakeReactionDao : ReactionDao {
    override fun observeAll(): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override suspend fun getAll(): List<ReactionEntity> = emptyList()
    override fun observeByMessage(messageId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override suspend fun getByMessage(messageId: Long): List<ReactionEntity> = emptyList()
    override suspend fun insert(reaction: ReactionEntity): Long = 0L
    override suspend fun delete(reaction: ReactionEntity) = Unit
    override suspend fun deleteByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String) = Unit
    override suspend fun getByEmoji(emoji: String): List<ReactionEntity> = emptyList()
    override suspend fun getTopEmojis(limit: Int): List<com.plusorminustwo.postmark.data.db.dao.EmojiCount> = emptyList()
    override fun observeTopEmojisBySender(senderAddress: String): Flow<List<com.plusorminustwo.postmark.data.db.dao.EmojiCount>> = flowOf(emptyList())
    override fun observeByThread(threadId: Long): Flow<List<ReactionEntity>> = flowOf(emptyList())
    override suspend fun getByThread(threadId: Long): List<ReactionEntity> = emptyList()
    override suspend fun deleteAll() = Unit
    override fun observeDistinctEmojis(): Flow<List<String>> = flowOf(emptyList())
}
