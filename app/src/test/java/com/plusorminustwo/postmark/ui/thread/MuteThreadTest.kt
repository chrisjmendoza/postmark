package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.db.entity.toDomain
import com.plusorminustwo.postmark.data.db.entity.toEntity
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Thread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MuteThreadTest {

    private lateinit var fakeDao: FakeMutableThreadDao
    private lateinit var repository: ThreadRepository

    @Before
    fun setUp() {
        fakeDao = FakeMutableThreadDao()
        repository = ThreadRepository(fakeDao)
    }

    // ── New thread defaults ───────────────────────────────────────────────────

    @Test
    fun `new ThreadEntity defaults to isMuted false`() {
        val entity = ThreadEntity(id = 1L, displayName = "Alice", address = "+1", lastMessageAt = 0L)
        assertFalse(entity.isMuted)
    }

    // ── updateMuted persists ──────────────────────────────────────────────────

    @Test
    fun `updateMuted persists true`() = runTest {
        repository.updateMuted(1L, true)
        assertEquals(1L to true, fakeDao.lastMuteUpdate)
    }

    @Test
    fun `updateMuted persists false`() = runTest {
        repository.updateMuted(1L, true)
        repository.updateMuted(1L, false)
        assertEquals(1L to false, fakeDao.lastMuteUpdate)
    }

    // ── Toggle logic ─────────────────────────────────────────────────────────

    @Test
    fun `toggle from false to true`() = runTest {
        fakeDao.muteState = false
        val current = fakeDao.muteState
        repository.updateMuted(1L, !current)
        assertEquals(1L to true, fakeDao.lastMuteUpdate)
    }

    @Test
    fun `toggle from true to false`() = runTest {
        fakeDao.muteState = true
        val current = fakeDao.muteState
        repository.updateMuted(1L, !current)
        assertEquals(1L to false, fakeDao.lastMuteUpdate)
    }

    // ── toDomain / toEntity mapping ───────────────────────────────────────────

    @Test
    fun `ThreadEntity with isMuted=true maps to Thread with isMuted=true`() {
        val entity = ThreadEntity(1L, "Alice", "+1", 0L, isMuted = true)
        assertTrue(entity.toDomain().isMuted)
    }

    @Test
    fun `ThreadEntity with isMuted=false maps to Thread with isMuted=false`() {
        val entity = ThreadEntity(1L, "Alice", "+1", 0L, isMuted = false)
        assertFalse(entity.toDomain().isMuted)
    }

    @Test
    fun `Thread with isMuted=true maps back to ThreadEntity with isMuted=true`() {
        val thread = Thread(id = 1L, displayName = "Bob", address = "+2", lastMessageAt = 0L, isMuted = true)
        assertTrue(thread.toEntity().isMuted)
    }

    @Test
    fun `Thread with isMuted=false maps back to ThreadEntity with isMuted=false`() {
        val thread = Thread(id = 1L, displayName = "Bob", address = "+2", lastMessageAt = 0L, isMuted = false)
        assertFalse(thread.toEntity().isMuted)
    }

    // ── Fake ─────────────────────────────────────────────────────────────────

    private class FakeMutableThreadDao : ThreadDao {
        var muteState = false
        var lastMuteUpdate: Pair<Long, Boolean>? = null

        override suspend fun updateMuted(threadId: Long, isMuted: Boolean) {
            lastMuteUpdate = threadId to isMuted
            muteState = isMuted
        }

        override fun observeAll(): Flow<List<ThreadEntity>> = flowOf(emptyList())
        override fun observeById(threadId: Long): Flow<ThreadEntity?> = flowOf(null)
        override suspend fun getById(threadId: Long): ThreadEntity? = null
        override suspend fun insert(t: ThreadEntity) {}
        override suspend fun insertAll(ts: List<ThreadEntity>) {}
        override suspend fun update(t: ThreadEntity) {}
        override suspend fun delete(t: ThreadEntity) {}
        override suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) {}
        override suspend fun getThreadsForBackup(): List<ThreadEntity> = emptyList()
        override suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity> = emptyList()
        override suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) {}
        override suspend fun updateLastMessagePreview(threadId: Long, preview: String) {}        override suspend fun updatePinned(threadId: Long, isPinned: Boolean) {}        override suspend fun deleteAll() {}
    }
}
