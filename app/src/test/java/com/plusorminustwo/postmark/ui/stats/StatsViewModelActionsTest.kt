package com.plusorminustwo.postmark.ui.stats

import androidx.lifecycle.SavedStateHandle
import com.plusorminustwo.postmark.data.db.dao.GlobalCounts
import com.plusorminustwo.postmark.data.db.dao.GlobalStatsDao
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Tests for [StatsViewModel] actions that are NOT covered by [StatsViewModelHeatmapTest]:
 *
 *  - [StatsViewModel.selectThread] scope switching and origin-scope restore on back
 *  - [StatsViewModel.selectThread] display-style carry-over
 *  - [StatsViewModel.setScope] origin reset so subsequent back navigates correctly
 *  - [StatsViewModel.setDisplayStyle] routing to globalStyle vs threadStyle
 *  - [StatsViewModel.heatmapByDayOfWeek] day-of-week aggregation
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelActionsTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(
        messages: List<MessageEntity> = emptyList(),
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): StatsViewModel {
        val dao = ActionsRangeFakeMessageDao(messages)
        return StatsViewModel(
            ActionsThreadDao(),
            dao,
            ActionsReactionDao(),
            StatsUpdater(dao, ActionsThreadStatsDao(), ActionsGlobalStatsDao(), ActionsReactionDao()),
            savedStateHandle
        )
    }

    // ── selectThread: entering drilldown ─────────────────────────────────────

    @Test
    fun `selectThread with id switches scope to PER_THREAD`() {
        val vm = makeViewModel()
        vm.selectThread(42L)
        assertEquals(StatsScope.PER_THREAD, vm.selectedScope.value)
    }

    @Test
    fun `selectThread with id sets selectedThreadId`() {
        val vm = makeViewModel()
        vm.selectThread(42L)
        assertEquals(42L, vm.selectedThreadId.value)
    }

    @Test
    fun `selectThread with id clears selectedHeatmapDays`() {
        val vm = makeViewModel()
        vm.toggleHeatmapDay(LocalDate.now())
        vm.selectThread(42L)
        assertTrue(vm.selectedHeatmapDays.value.isEmpty())
    }

    @Test
    fun `selectThread with id carries NUMBERS style from GLOBAL`() {
        val vm = makeViewModel()
        // globalStyle is NUMBERS by default
        vm.selectThread(42L)
        assertEquals(StatsDisplayStyle.NUMBERS, vm.threadStyle.value)
    }

    @Test
    fun `selectThread with id carries CHARTS style from GLOBAL`() {
        val vm = makeViewModel()
        vm.setDisplayStyle(StatsDisplayStyle.CHARTS)
        vm.selectThread(42L)
        assertEquals(StatsDisplayStyle.CHARTS, vm.threadStyle.value)
    }

    @Test
    fun `selectThread with id carries HEATMAP style from GLOBAL`() {
        val vm = makeViewModel()
        vm.setDisplayStyle(StatsDisplayStyle.HEATMAP)
        vm.selectThread(42L)
        assertEquals(StatsDisplayStyle.HEATMAP, vm.threadStyle.value)
    }

    // ── selectThread: exiting drilldown (back navigation) ────────────────────

    @Test
    fun `selectThread null after GLOBAL drilldown restores scope to GLOBAL`() {
        val vm = makeViewModel()
        // Default scope is GLOBAL; drill in from there
        vm.selectThread(42L)
        vm.selectThread(null) // back
        assertEquals(StatsScope.GLOBAL, vm.selectedScope.value)
    }

    @Test
    fun `selectThread null clears selectedThreadId`() {
        val vm = makeViewModel()
        vm.selectThread(42L)
        vm.selectThread(null)
        assertNull(vm.selectedThreadId.value)
    }

    @Test
    fun `selectThread null clears selectedHeatmapDays`() {
        val vm = makeViewModel()
        vm.selectThread(42L)
        vm.toggleHeatmapDay(LocalDate.now())
        vm.selectThread(null)
        assertTrue(vm.selectedHeatmapDays.value.isEmpty())
    }

    @Test
    fun `selectThread null from PER_THREAD list drilldown restores scope to PER_THREAD`() {
        val vm = makeViewModel()
        vm.setScope(StatsScope.PER_THREAD)  // user taps "Per thread" tab → now on thread list
        vm.selectThread(42L)                // taps a thread in the list → drilldown
        vm.selectThread(null)               // back
        assertEquals(StatsScope.PER_THREAD, vm.selectedScope.value)
    }

    @Test
    fun `selectThread null lands on thread list not on a specific thread`() {
        val vm = makeViewModel()
        vm.setScope(StatsScope.PER_THREAD)
        vm.selectThread(42L)
        vm.selectThread(null)
        assertNull(vm.selectedThreadId.value)
    }

    // ── selectThread: style is NOT copied when already in PER_THREAD ─────────

    @Test
    fun `selectThread from PER_THREAD list does not overwrite threadStyle with globalStyle`() {
        val vm = makeViewModel()
        // Arrive at PER_THREAD with HEATMAP as global style
        vm.setDisplayStyle(StatsDisplayStyle.HEATMAP)        // globalStyle = HEATMAP
        vm.setScope(StatsScope.PER_THREAD)                   // copies HEATMAP → threadStyle
        // Now manually change globalStyle while in PER_THREAD scope
        // (simulate user swapping back to GLOBAL briefly in some code path—
        //  we set globalStyle to NUMBERS to distinguish from threadStyle)
        // The only way to change globalStyle is to be in GLOBAL scope:
        vm.setScope(StatsScope.GLOBAL)                       // resets origin to GLOBAL
        vm.setDisplayStyle(StatsDisplayStyle.NUMBERS)        // globalStyle = NUMBERS
        vm.setScope(StatsScope.PER_THREAD)                   // threadStyle = NUMBERS (copies)
        // Now change threadStyle independently by doing a drill and back
        vm.selectThread(5L)                                  // drilldown; threadStyle stays NUMBERS
        vm.setDisplayStyle(StatsDisplayStyle.CHARTS)         // threadStyle = CHARTS (in PER_THREAD)
        vm.selectThread(null)                                 // back to PER_THREAD list
        // Drill again from PER_THREAD — should NOT copy globalStyle (NUMBERS) over threadStyle (CHARTS)
        vm.selectThread(99L)
        assertEquals(
            "threadStyle should stay CHARTS; selectThread from PER_THREAD must not copy globalStyle",
            StatsDisplayStyle.CHARTS,
            vm.threadStyle.value
        )
    }

    // ── setScope resets origin ────────────────────────────────────────────────

    @Test
    fun `setScope GLOBAL before drill means back returns to GLOBAL`() {
        val vm = makeViewModel()
        vm.setScope(StatsScope.PER_THREAD)  // go to thread list
        vm.setScope(StatsScope.GLOBAL)      // switch back (origin reset to GLOBAL)
        vm.selectThread(42L)                // drill from GLOBAL
        vm.selectThread(null)               // back
        assertEquals(StatsScope.GLOBAL, vm.selectedScope.value)
    }

    @Test
    fun `setScope PER_THREAD before drill means back returns to PER_THREAD`() {
        val vm = makeViewModel()
        vm.setScope(StatsScope.PER_THREAD)  // origin now PER_THREAD
        vm.selectThread(42L)                // drill
        vm.selectThread(null)               // back
        assertEquals(StatsScope.PER_THREAD, vm.selectedScope.value)
    }

    @Test
    fun `setScope PER_THREAD copies globalStyle to threadStyle`() {
        val vm = makeViewModel()
        vm.setDisplayStyle(StatsDisplayStyle.CHARTS)  // globalStyle = CHARTS
        vm.setScope(StatsScope.PER_THREAD)
        assertEquals(StatsDisplayStyle.CHARTS, vm.threadStyle.value)
    }

    @Test
    fun `setScope resets selectedThreadId to null`() {
        val vm = makeViewModel()
        vm.selectThread(42L)
        vm.setScope(StatsScope.GLOBAL)
        assertNull(vm.selectedThreadId.value)
    }

    @Test
    fun `setScope resets directThreadNavigation to false`() {
        val vm = makeViewModel()
        vm.preSelectThread(42L)
        vm.setScope(StatsScope.GLOBAL)
        assertFalse(vm.directThreadNavigation.value)
    }

    @Test
    fun `setScope clears selectedHeatmapDays`() {
        val vm = makeViewModel()
        vm.toggleHeatmapDay(LocalDate.now())
        vm.setScope(StatsScope.PER_THREAD)
        assertTrue(vm.selectedHeatmapDays.value.isEmpty())
    }

    // ── setDisplayStyle routing ───────────────────────────────────────────────

    @Test
    fun `setDisplayStyle in GLOBAL scope updates globalStyle`() {
        val vm = makeViewModel()
        vm.setDisplayStyle(StatsDisplayStyle.CHARTS)
        assertEquals(StatsDisplayStyle.CHARTS, vm.globalStyle.value)
    }

    @Test
    fun `setDisplayStyle in GLOBAL scope does not change threadStyle`() {
        val vm = makeViewModel()
        val before = vm.threadStyle.value
        vm.setDisplayStyle(StatsDisplayStyle.CHARTS)
        assertEquals(before, vm.threadStyle.value)
    }

    @Test
    fun `setDisplayStyle in PER_THREAD scope updates threadStyle`() {
        val vm = makeViewModel()
        vm.selectThread(42L)  // scope → PER_THREAD
        vm.setDisplayStyle(StatsDisplayStyle.CHARTS)
        assertEquals(StatsDisplayStyle.CHARTS, vm.threadStyle.value)
    }

    @Test
    fun `setDisplayStyle in PER_THREAD scope does not change globalStyle`() {
        val vm = makeViewModel()
        val before = vm.globalStyle.value
        vm.selectThread(42L)
        vm.setDisplayStyle(StatsDisplayStyle.CHARTS)
        assertEquals(before, vm.globalStyle.value)
    }

    @Test
    fun `setDisplayStyle cycles through all styles independently per scope`() {
        val vm = makeViewModel()
        // Set all three global styles
        for (style in StatsDisplayStyle.entries) {
            vm.setDisplayStyle(style)
            assertEquals(style, vm.globalStyle.value)
        }
        // Enter per-thread and set all three thread styles
        vm.selectThread(1L)
        for (style in StatsDisplayStyle.entries) {
            vm.setDisplayStyle(style)
            assertEquals(style, vm.threadStyle.value)
        }
    }
}

// ── heatmapByDayOfWeek flow tests (require flow collection) ──────────────────

/**
 * Tests for [StatsViewModel.heatmapByDayOfWeek], which is a derived StateFlow that
 * aggregates heatmap messages (already month-scoped) by day-of-week (Mon=0..Sun=6).
 *
 * These use [runTest] + [launch] to subscribe to the flow and force upstream activation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelHeatmapDowTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // April 2026 anchors (verified):
    //   April  6 = Monday   (dow index 0)
    //   April  7 = Tuesday  (dow index 1)
    //   April  8 = Wednesday(dow index 2)
    //   April 11 = Saturday (dow index 5)
    //   April 12 = Sunday   (dow index 6)
    private val testMonth = YearMonth.of(2026, 4)

    private fun tsFor(date: LocalDate): Long =
        date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun msg(id: Long, date: LocalDate) = MessageEntity(
        id = id, threadId = 1L, address = "555", body = "hi",
        timestamp = tsFor(date), isSent = false
    )

    private fun makeViewModel(messages: List<MessageEntity>): StatsViewModel {
        val dao = ActionsRangeFakeMessageDao(messages)
        return StatsViewModel(
            ActionsThreadDao(),
            dao,
            ActionsReactionDao(),
            StatsUpdater(dao, ActionsThreadStatsDao(), ActionsGlobalStatsDao(), ActionsReactionDao()),
            SavedStateHandle()
        )
    }

    /** Collect the first non-initial emission (or the initial) by subscribing and cancelling. */
    private suspend fun collectDow(vm: StatsViewModel): IntArray {
        val emitted = mutableListOf<IntArray>()
        val job = vm.heatmapByDayOfWeek
            .let { flow ->
                kotlinx.coroutines.GlobalScope.launch(testDispatcher) {
                    flow.collect { emitted += it.clone() }
                }
            }
        job.cancel()
        job.join()
        return emitted.lastOrNull() ?: IntArray(7)
    }

    @Test
    fun `heatmapByDayOfWeek default value is all zeros`() = runTest(testDispatcher) {
        val vm = makeViewModel(emptyList())
        val emitted = mutableListOf<IntArray>()
        val job = launch { vm.heatmapByDayOfWeek.collect { emitted += it.clone() } }
        job.cancel()
        job.join()
        val result = emitted.lastOrNull() ?: IntArray(7)
        assertArrayEquals(IntArray(7), result)
    }

    @Test
    fun `heatmapByDayOfWeek counts Monday message at index 0`() = runTest(testDispatcher) {
        val vm = makeViewModel(listOf(msg(1L, LocalDate.of(2026, 4, 6))))
        vm.setHeatmapMonth(testMonth)
        val emitted = mutableListOf<IntArray>()
        val job = launch { vm.heatmapByDayOfWeek.collect { emitted += it.clone() } }
        job.cancel()
        job.join()
        val dow = emitted.last()
        assertEquals("Monday (index 0) should be 1", 1, dow[0])
        assertEquals("Tuesday (index 1) should be 0", 0, dow[1])
    }

    @Test
    fun `heatmapByDayOfWeek counts Sunday message at index 6`() = runTest(testDispatcher) {
        val vm = makeViewModel(listOf(msg(1L, LocalDate.of(2026, 4, 12))))
        vm.setHeatmapMonth(testMonth)
        val emitted = mutableListOf<IntArray>()
        val job = launch { vm.heatmapByDayOfWeek.collect { emitted += it.clone() } }
        job.cancel()
        job.join()
        val dow = emitted.last()
        assertEquals("Sunday (index 6) should be 1", 1, dow[6])
        assertEquals("Monday (index 0) should be 0", 0, dow[0])
    }

    @Test
    fun `heatmapByDayOfWeek accumulates multiple messages on same day`() = runTest(testDispatcher) {
        val mon = LocalDate.of(2026, 4, 6)
        val vm = makeViewModel(listOf(
            msg(1L, mon),
            MessageEntity(2L, 1L, "555", "hi2", tsFor(mon) + 60_000, false),
            MessageEntity(3L, 1L, "555", "hi3", tsFor(mon) + 120_000, false)
        ))
        vm.setHeatmapMonth(testMonth)
        val emitted = mutableListOf<IntArray>()
        val job = launch { vm.heatmapByDayOfWeek.collect { emitted += it.clone() } }
        job.cancel()
        job.join()
        assertEquals("3 messages on Monday → index 0 = 3", 3, emitted.last()[0])
    }

    @Test
    fun `heatmapByDayOfWeek distributes messages across different days correctly`() = runTest(testDispatcher) {
        val mon = LocalDate.of(2026, 4, 6)   // index 0
        val tue = LocalDate.of(2026, 4, 7)   // index 1
        val sat = LocalDate.of(2026, 4, 11)  // index 5
        val vm = makeViewModel(listOf(
            msg(1L, mon),
            msg(2L, mon),
            msg(3L, tue),
            msg(4L, sat),
            msg(5L, sat),
            msg(6L, sat)
        ))
        vm.setHeatmapMonth(testMonth)
        val emitted = mutableListOf<IntArray>()
        val job = launch { vm.heatmapByDayOfWeek.collect { emitted += it.clone() } }
        job.cancel()
        job.join()
        val dow = emitted.last()
        assertEquals("Mon: 2", 2, dow[0])
        assertEquals("Tue: 1", 1, dow[1])
        assertEquals("Wed: 0", 0, dow[2])
        assertEquals("Thu: 0", 0, dow[3])
        assertEquals("Fri: 0", 0, dow[4])
        assertEquals("Sat: 3", 3, dow[5])
        assertEquals("Sun: 0", 0, dow[6])
    }

    @Test
    fun `heatmapByDayOfWeek excludes messages outside the current month`() = runTest(testDispatcher) {
        val aprilMonday = LocalDate.of(2026, 4, 6)
        val marchDay    = LocalDate.of(2026, 3, 9)  // also a Monday, but in March
        val vm = makeViewModel(listOf(msg(1L, aprilMonday), msg(2L, marchDay)))
        vm.setHeatmapMonth(testMonth)
        val emitted = mutableListOf<IntArray>()
        val job = launch { vm.heatmapByDayOfWeek.collect { emitted += it.clone() } }
        job.cancel()
        job.join()
        // Only the April message should be counted; March is outside the heatmap window
        assertEquals("Only April Monday counts; March excluded → index 0 = 1", 1, emitted.last()[0])
    }

    @Test
    fun `heatmapByDayOfWeek resets to zeros when switching to a month with no messages`() = runTest(testDispatcher) {
        val mon = LocalDate.of(2026, 4, 6)
        val vm = makeViewModel(listOf(msg(1L, mon)))
        vm.setHeatmapMonth(testMonth)

        val emitted = mutableListOf<IntArray>()
        val job = launch { vm.heatmapByDayOfWeek.collect { emitted += it.clone() } }
        job.cancel()
        job.join()
        // Verify April had a count
        assertTrue("Should have April data before reset", emitted.last()[0] >= 1)

        // Now switch to a month with no messages
        val emittedAfter = mutableListOf<IntArray>()
        val job2 = launch { vm.heatmapByDayOfWeek.collect { emittedAfter += it.clone() } }
        vm.setHeatmapMonth(YearMonth.of(2025, 1))
        job2.cancel()
        job2.join()
        assertArrayEquals("After switching to empty month all counts should be 0", IntArray(7), emittedAfter.last())
    }
}

// ── Fake DAO implementations (scoped to this file) ───────────────────────────

/**
 * Message DAO that returns a pre-loaded list filtered by timestamp range.
 * Used for DOW tests that need real message data in [heatmapMessages].
 */
private class ActionsRangeFakeMessageDao(
    private val allMessages: List<MessageEntity> = emptyList()
) : MessageDao {
    override fun observeByThread(threadId: Long): Flow<List<MessageEntity>> =
        flowOf(allMessages.filter { it.threadId == threadId })

    override fun observeMessagesFrom(startMs: Long): Flow<List<MessageEntity>> =
        flowOf(allMessages.filter { it.timestamp >= startMs })

    override fun observeMessagesFromForThread(threadId: Long, startMs: Long): Flow<List<MessageEntity>> =
        flowOf(allMessages.filter { it.threadId == threadId && it.timestamp >= startMs })

    override fun observeMessagesInRange(startMs: Long, endMs: Long): Flow<List<MessageEntity>> =
        flowOf(allMessages.filter { it.timestamp in startMs until endMs })

    override fun observeMessagesInRangeForThread(threadId: Long, startMs: Long, endMs: Long): Flow<List<MessageEntity>> =
        flowOf(allMessages.filter { it.threadId == threadId && it.timestamp in startMs until endMs })

    override suspend fun getByThread(threadId: Long): List<MessageEntity> =
        allMessages.filter { it.threadId == threadId }

    override suspend fun getById(messageId: Long): MessageEntity? =
        allMessages.firstOrNull { it.id == messageId }

    override suspend fun getAll(): List<MessageEntity> = allMessages
    override suspend fun getAllThreadIds(): List<Long> = allMessages.map { it.threadId }.distinct()

    override suspend fun insert(message: MessageEntity): Long = 0L
    override suspend fun insertAll(messages: List<MessageEntity>) = Unit
    override suspend fun delete(message: MessageEntity) = Unit
    override suspend fun deleteByThread(threadId: Long) = Unit
    override suspend fun countByThread(threadId: Long): Int = 0
    override suspend fun getByThreadAndDateRange(threadId: Long, startMs: Long, endMs: Long): List<MessageEntity> =
        allMessages.filter { it.threadId == threadId && it.timestamp in startMs until endMs }
    override suspend fun getActiveDatesForThread(threadId: Long): List<String> = emptyList()
    override suspend fun getLatestForThread(threadId: Long): MessageEntity? = null
    override suspend fun getLatestNForThread(threadId: Long, n: Int): List<MessageEntity> = emptyList()
    override suspend fun getLatestBeforeForThread(threadId: Long, timestamp: Long): MessageEntity? = null
    override suspend fun updateDeliveryStatus(messageId: Long, status: Int) = Unit
    override suspend fun deleteOptimisticMessages(threadId: Long) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun getMaxId(): Long? = null
    override suspend fun getMaxMmsId(): Long? = null
    override suspend fun getMinMmsId(): Long? = null
    override suspend fun deleteById(messageId: Long) = Unit
    override suspend fun getLatestNonReactionForThread(threadId: Long): MessageEntity? = null
}

private class ActionsThreadDao : ThreadDao {
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
    override suspend fun isMutedByAddress(address: String): Boolean? = null
    override suspend fun isNotificationsEnabledByAddress(address: String): Boolean? = null
    override suspend fun updateNotificationsEnabled(threadId: Long, enabled: Boolean) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun count(): Int = 0
}

private class ActionsThreadStatsDao : ThreadStatsDao {
    override fun observeByThread(threadId: Long): Flow<ThreadStatsEntity?> = flowOf(null)
    override fun observeAll(): Flow<List<ThreadStatsEntity>> = flowOf(emptyList())
    override suspend fun getByThread(threadId: Long): ThreadStatsEntity? = null
    override suspend fun upsert(stats: ThreadStatsEntity) = Unit
    override suspend fun update(stats: ThreadStatsEntity) = Unit
    override suspend fun getGlobalCounts(): GlobalCounts? = null
}

private class ActionsGlobalStatsDao : GlobalStatsDao {
    override fun observe(): Flow<GlobalStatsEntity?> = flowOf(null)
    override suspend fun get(): GlobalStatsEntity? = null
    override suspend fun upsert(stats: GlobalStatsEntity) = Unit
}

private class ActionsReactionDao : ReactionDao {
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
    override suspend fun countByMessageSenderAndEmoji(messageId: Long, senderAddress: String, emoji: String): Int = 0
}
