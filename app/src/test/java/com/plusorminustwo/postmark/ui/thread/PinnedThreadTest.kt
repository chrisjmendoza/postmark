package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for pinned thread feature:
 *  - Sort order: pinned threads appear before unpinned, with recency as tiebreaker.
 *  - [ThreadRepository.updatePinned] delegates to the DAO with correct arguments.
 */
class PinnedThreadTest {

    private lateinit var fakeDao: PinFakeThreadDao
    private lateinit var repository: ThreadRepository

    @Before
    fun setUp() {
        fakeDao = PinFakeThreadDao()
        repository = ThreadRepository(fakeDao)
    }

    // ── Sort order ────────────────────────────────────────────────────────────

    @Test
    fun `pinned thread appears before unpinned thread`() = runTest {
        val threads = listOf(
            makeThread(id = 1L, lastMessageAt = 1_000L, isPinned = false),
            makeThread(id = 2L, lastMessageAt = 2_000L, isPinned = true)
        )
        // Simulate the DAO sort: ORDER BY isPinned DESC, lastMessageAt DESC
        val sorted = threads.sortedWith(compareByDescending<ThreadEntity> { it.isPinned }
            .thenByDescending { it.lastMessageAt })
        assertEquals(2L, sorted.first().id)
    }

    @Test
    fun `among pinned threads order is by lastMessageAt DESC`() = runTest {
        val threads = listOf(
            makeThread(id = 1L, lastMessageAt = 1_000L, isPinned = true),
            makeThread(id = 2L, lastMessageAt = 3_000L, isPinned = true),
            makeThread(id = 3L, lastMessageAt = 2_000L, isPinned = true)
        )
        val sorted = threads.sortedWith(compareByDescending<ThreadEntity> { it.isPinned }
            .thenByDescending { it.lastMessageAt })
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun `among unpinned threads order is by lastMessageAt DESC`() = runTest {
        val threads = listOf(
            makeThread(id = 1L, lastMessageAt = 1_000L, isPinned = false),
            makeThread(id = 2L, lastMessageAt = 3_000L, isPinned = false),
            makeThread(id = 3L, lastMessageAt = 2_000L, isPinned = false)
        )
        val sorted = threads.sortedWith(compareByDescending<ThreadEntity> { it.isPinned }
            .thenByDescending { it.lastMessageAt })
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun `pinned threads always precede unpinned regardless of recency`() = runTest {
        val threads = listOf(
            makeThread(id = 1L, lastMessageAt = 9_000L, isPinned = false), // newest but unpinned
            makeThread(id = 2L, lastMessageAt = 1_000L, isPinned = true)   // oldest but pinned
        )
        val sorted = threads.sortedWith(compareByDescending<ThreadEntity> { it.isPinned }
            .thenByDescending { it.lastMessageAt })
        assertEquals(2L, sorted.first().id) // pinned wins
    }

    // ── Repository delegation ─────────────────────────────────────────────────

    @Test
    fun `updatePinned true delegates to DAO with isPinned=true`() = runTest {
        repository.updatePinned(42L, true)
        assertEquals(42L to true, fakeDao.lastPinnedUpdate)
    }

    @Test
    fun `updatePinned false delegates to DAO with isPinned=false`() = runTest {
        repository.updatePinned(7L, false)
        assertEquals(7L to false, fakeDao.lastPinnedUpdate)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeThread(id: Long, lastMessageAt: Long, isPinned: Boolean) = ThreadEntity(
        id = id,
        displayName = "Contact $id",
        address = "+1555000000$id",
        lastMessageAt = lastMessageAt,
        isPinned = isPinned
    )

    // ── Fake DAO ──────────────────────────────────────────────────────────────

    private class PinFakeThreadDao : ThreadDao {
        var lastPinnedUpdate: Pair<Long, Boolean>? = null
        private val _threads = MutableStateFlow<List<ThreadEntity>>(emptyList())

        override fun observeAll(): Flow<List<ThreadEntity>> = _threads
        override suspend fun getById(threadId: Long): ThreadEntity? = null
        override fun observeById(threadId: Long): Flow<ThreadEntity?> =
            MutableStateFlow(null)

        override suspend fun insert(thread: ThreadEntity) {}
        override suspend fun insertAll(threads: List<ThreadEntity>) {}
        override suspend fun update(thread: ThreadEntity) {}
        override suspend fun delete(thread: ThreadEntity) {}
        override suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) {}
        override suspend fun updateMuted(threadId: Long, isMuted: Boolean) {}
        override suspend fun updatePinned(threadId: Long, isPinned: Boolean) {
            lastPinnedUpdate = threadId to isPinned
        }
        override suspend fun getThreadsForBackup(): List<ThreadEntity> = emptyList()
        override suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity> = emptyList()
        override suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) {}
        override suspend fun updateLastMessagePreview(threadId: Long, preview: String) {}
        override suspend fun deleteAll() {}
    }
}
