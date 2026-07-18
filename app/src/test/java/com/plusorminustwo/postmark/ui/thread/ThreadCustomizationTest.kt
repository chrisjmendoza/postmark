package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the Phase A customization fields:
 *  - [ThreadRepository.setAccentColor] delegates to the DAO with correct values.
 *  - [ThreadRepository.setChatBackground] delegates to the DAO with correct values.
 */
class ThreadCustomizationTest {

    private lateinit var fakeDao: CustomizationFakeThreadDao
    private lateinit var repository: ThreadRepository

    @Before
    fun setUp() {
        fakeDao = CustomizationFakeThreadDao()
        repository = ThreadRepository(fakeDao)
    }

    // ── setAccentColor ───────────────────────────────────────────────────────

    @Test
    fun `setAccentColor delegates to DAO with the given argb`() = runTest {
        repository.setAccentColor(42L, 0xFFAA00FF.toInt())
        assertEquals(42L to 0xFFAA00FF.toInt(), fakeDao.lastAccentUpdate)
    }

    @Test
    fun `setAccentColor with null clears the accent`() = runTest {
        repository.setAccentColor(42L, 0xFFAA00FF.toInt())
        repository.setAccentColor(42L, null)
        assertEquals(42L to null, fakeDao.lastAccentUpdate)
    }

    // ── setChatBackground ────────────────────────────────────────────────────

    @Test
    fun `setChatBackground delegates to DAO with the given id`() = runTest {
        repository.setChatBackground(7L, "sunset")
        assertEquals(7L to "sunset", fakeDao.lastBackgroundUpdate)
    }

    @Test
    fun `setChatBackground with null clears the background`() = runTest {
        repository.setChatBackground(7L, "sunset")
        repository.setChatBackground(7L, null)
        assertEquals(7L to null, fakeDao.lastBackgroundUpdate)
    }

    // ── Fake DAO ──────────────────────────────────────────────────────────────

    private class CustomizationFakeThreadDao : ThreadDao {
        var lastAccentUpdate: Pair<Long, Int?>? = null
        var lastBackgroundUpdate: Pair<Long, String?>? = null
        private val _threads = MutableStateFlow<List<ThreadEntity>>(emptyList())

        override fun observeAll(): Flow<List<ThreadEntity>> = _threads
        override suspend fun getById(threadId: Long): ThreadEntity? = null
        override fun observeById(threadId: Long): Flow<ThreadEntity?> = MutableStateFlow(null)

        override suspend fun insert(thread: ThreadEntity) {}
        override suspend fun insertAll(threads: List<ThreadEntity>) {}
        override suspend fun insertIgnore(thread: ThreadEntity) {}
        override suspend fun insertAllIgnore(threads: List<ThreadEntity>) {}
        override suspend fun update(thread: ThreadEntity) {}
        override suspend fun delete(thread: ThreadEntity) {}
        override suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) {}
        override suspend fun updateMuted(threadId: Long, isMuted: Boolean) {}
        override suspend fun updatePinned(threadId: Long, isPinned: Boolean) {}
        override suspend fun getThreadsForBackup(): List<ThreadEntity> = emptyList()
        override suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity> = emptyList()
        override suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) {}
        override suspend fun updateLastMessagePreview(threadId: Long, preview: String) {}
        override suspend fun isMutedByAddress(address: String): Boolean? = null
        override suspend fun isNotificationsEnabledByAddress(address: String): Boolean? = null
        override suspend fun getDisplayNameByAddress(address: String): String? = null
        override suspend fun updateNotificationsEnabled(threadId: Long, enabled: Boolean) {}
        override suspend fun deleteAll() {}
        override suspend fun count(): Int = 0
        override suspend fun updateNickname(threadId: Long, nickname: String?) {}

        override suspend fun updateAccentColor(threadId: Long, argb: Int?) {
            lastAccentUpdate = threadId to argb
        }

        override suspend fun updateChatBackground(threadId: Long, backgroundId: String?) {
            lastBackgroundUpdate = threadId to backgroundId
        }
    }
}
