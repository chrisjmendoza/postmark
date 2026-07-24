package com.plusorminustwo.postmark.ui.stats

import androidx.lifecycle.SavedStateHandle
import com.plusorminustwo.postmark.data.db.dao.EmojiBodyRow
import com.plusorminustwo.postmark.data.db.dao.GlobalAggregateRow
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.MessageMeta
import com.plusorminustwo.postmark.data.db.dao.StatsDao
import com.plusorminustwo.postmark.data.db.dao.ThreadAggregateRow
import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.dao.ThreadEmojiRow
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
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
        return StatsViewModel(threadDao, messageDao, FakeStatsDao(), savedStateHandle)
    }

    // ── Default state ─────────────────────────────────────────────────────

    @Test
    fun `default heatmapMonth is current month`() {
        val vm = makeViewModel()
        assertEquals(YearMonth.now(), vm.heatmapMonth.value)
    }

    @Test
    fun `default heatmap selection is empty`() {
        val vm = makeViewModel()
        assertTrue(vm.heatmapSelection.value.days.isEmpty())
        assertFalse(vm.heatmapSelection.value.multiSelect)
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
    fun `setHeatmapMonth clears the day selection`() {
        val vm = makeViewModel()
        vm.tapHeatmapDay(LocalDate.now())
        vm.setHeatmapMonth(YearMonth.now().minusMonths(1))
        assertTrue(vm.heatmapSelection.value.days.isEmpty())
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

    // ── Day selection (tap / long-press wiring; full semantics in HeatmapSelectionTest) ──

    @Test
    fun `tapHeatmapDay selects only the tapped day`() {
        val vm   = makeViewModel()
        val day1 = LocalDate.now()
        val day2 = day1.minusDays(1)
        vm.tapHeatmapDay(day1)
        vm.tapHeatmapDay(day2)
        assertEquals(setOf(day2), vm.heatmapSelection.value.days)
        assertFalse(vm.heatmapSelection.value.multiSelect)
    }

    @Test
    fun `longPressHeatmapDay enters multi-select and taps toggle days`() {
        val vm   = makeViewModel()
        val day1 = LocalDate.now()
        val day2 = day1.minusDays(3)
        vm.longPressHeatmapDay(day1)
        vm.tapHeatmapDay(day2)
        assertEquals(setOf(day1, day2), vm.heatmapSelection.value.days)
        assertTrue(vm.heatmapSelection.value.multiSelect)
    }

    @Test
    fun `tap then long-press selects the date range`() {
        val vm    = makeViewModel()
        val start = LocalDate.now().minusDays(3)
        val end   = LocalDate.now()
        vm.tapHeatmapDay(start)
        vm.longPressHeatmapDay(end)
        assertEquals(4, vm.heatmapSelection.value.days.size)
        assertTrue(vm.heatmapSelection.value.multiSelect)
    }

    @Test
    fun `clearHeatmapDays empties the selection and exits multi-select`() {
        val vm = makeViewModel()
        vm.longPressHeatmapDay(LocalDate.now())
        vm.tapHeatmapDay(LocalDate.now().minusDays(1))
        vm.clearHeatmapDays()
        assertTrue(vm.heatmapSelection.value.days.isEmpty())
        assertFalse(vm.heatmapSelection.value.multiSelect)
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
    fun `setScope GLOBAL clears the day selection`() {
        val vm = makeViewModel()
        vm.tapHeatmapDay(LocalDate.now())
        vm.setScope(StatsScope.GLOBAL)
        assertTrue(vm.heatmapSelection.value.days.isEmpty())
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
    override suspend fun updateDeliveryStatus(messageId: Long, status: Int) = Unit
    override suspend fun updateThreadId(messageId: Long, threadId: Long) = Unit
    override suspend fun deleteOptimisticMessages(threadId: Long, isMms: Boolean) = Unit
    override suspend fun getOptimisticSentDeliveryStatus(threadId: Long, isMms: Boolean): Int? = null
    override suspend fun getOptimisticSentId(threadId: Long, isMms: Boolean): Long? = null
    override suspend fun getOptimisticSentMms(threadId: Long): List<MessageEntity> = emptyList()
    override suspend fun updateAttachments(messageId: Long, attachmentsJson: String?, firstUri: String?, firstMime: String?) = Unit
    override suspend fun getEmptyMmsRows(limit: Int): List<MessageEntity> = emptyList()
    override suspend fun updateBody(messageId: Long, body: String) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun getAllThreadIds(): List<Long> = emptyList()
    override suspend fun getAll(): List<MessageEntity> = emptyList()
    override suspend fun getQueuedMessages(): List<MessageEntity> = emptyList()
    override suspend fun hasQueuedInThread(threadId: Long): Boolean = false
    override suspend fun getMaxId(): Long? = null
    override suspend fun getMaxMmsId(): Long? = null
    override suspend fun getMinMmsId(): Long? = null
    override suspend fun hasAnyMessages(): Boolean = false
    override suspend fun getMaxRestoredId(): Long? = null
    override suspend fun deleteById(messageId: Long) = Unit
    override suspend fun deleteByIds(ids: List<Long>) = Unit
    override suspend fun getLatestForThread(threadId: Long): MessageEntity? = null
    override suspend fun markAllRead(threadId: Long) = Unit
    override suspend fun markLatestUnread(threadId: Long) = Unit
    override fun observeUnreadCounts(): Flow<List<com.plusorminustwo.postmark.data.db.dao.UnreadCount>> = flowOf(emptyList())
    override fun observeMediaMessages(threadId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
    override suspend fun getAllWithAttachments(): List<MessageEntity> = emptyList()
    override suspend fun getMessageCountsByThread(): List<com.plusorminustwo.postmark.data.db.dao.ThreadMessageCount> = emptyList()
    override suspend fun updateStarred(messageId: Long, isStarred: Boolean) = Unit
    override fun observeStarredMedia(): Flow<List<MessageEntity>> = flowOf(emptyList())
    override suspend fun updatePinned(messageId: Long, isPinned: Boolean) = Unit
    override fun observePinnedByThread(threadId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
}

private class FakeThreadDao : ThreadDao {
    override fun observeAll(): Flow<List<ThreadEntity>> = flowOf(emptyList())
    override fun observeById(threadId: Long): Flow<ThreadEntity?> = flowOf(null)
    override suspend fun getById(threadId: Long): ThreadEntity? = null
    override suspend fun getAll(): List<ThreadEntity> = emptyList()
    override suspend fun updateDisplayName(threadId: Long, displayName: String) {}
    override suspend fun insert(thread: ThreadEntity) = Unit
    override suspend fun insertAll(threads: List<ThreadEntity>) = Unit
    override suspend fun insertIgnore(thread: ThreadEntity) = Unit
    override suspend fun insertAllIgnore(threads: List<ThreadEntity>) = Unit
    override suspend fun update(thread: ThreadEntity) = Unit
    override suspend fun delete(thread: ThreadEntity) = Unit
    override suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) = Unit
    override suspend fun updateMuted(threadId: Long, isMuted: Boolean) = Unit
    override suspend fun updatePinned(threadId: Long, isPinned: Boolean) = Unit
    override suspend fun getThreadsForBackup(): List<ThreadEntity> = emptyList()
    override suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity> = emptyList()
    override suspend fun getThreadsWithParticipants(): List<ThreadEntity> = emptyList()
    override suspend fun updateRoster(threadId: Long, participantsJson: String?, displayName: String) {}
    override suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) = Unit
    override suspend fun updateLastMessagePreview(threadId: Long, preview: String) = Unit
    override suspend fun updateNotificationsEnabled(threadId: Long, enabled: Boolean) = Unit
    override fun observeNonSpam(): Flow<List<ThreadEntity>> = observeAll()
    override fun observeSpam(): Flow<List<ThreadEntity>> = observeAll()
    override suspend fun updateSpam(threadId: Long, isSpam: Boolean) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun count(): Int = 0
    override suspend fun updateNickname(threadId: Long, nickname: String?) = Unit
    override suspend fun updateAccentColor(threadId: Long, argb: Int?) = Unit
    override suspend fun updateChatBackground(threadId: Long, backgroundId: String?) = Unit
    override suspend fun countByChatBackground(id: String): Int = 0
    override suspend fun updateSentColor(threadId: Long, argb: Int?) = Unit
}

private class FakeStatsDao : StatsDao {
    override fun observeThreadAggregates(): Flow<List<ThreadAggregateRow>> = flowOf(emptyList())
    override fun observeGlobalAggregates(): Flow<GlobalAggregateRow> = flowOf(GlobalAggregateRow(0, 0, 0L, 0L))
    override fun observeMessageMetas(): Flow<List<MessageMeta>> = flowOf(emptyList())
    override fun observeEmojiBodies(): Flow<List<EmojiBodyRow>> = flowOf(emptyList())
    override fun observeReactionEmojisByThread(): Flow<List<ThreadEmojiRow>> = flowOf(emptyList())
    override fun observeMessageMetasInRange(startMs: Long, endMs: Long): Flow<List<MessageMeta>> = flowOf(emptyList())
    override fun observeMessageMetasInRangeForThread(threadId: Long, startMs: Long, endMs: Long): Flow<List<MessageMeta>> = flowOf(emptyList())
}
