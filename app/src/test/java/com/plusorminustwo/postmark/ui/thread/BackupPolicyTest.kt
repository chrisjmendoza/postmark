package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BackupPolicyTest {

    private lateinit var fakeDao: FakeThreadDao
    private lateinit var repository: ThreadRepository

    @Before
    fun setUp() {
        fakeDao = FakeThreadDao()
        repository = ThreadRepository(fakeDao)
    }

    @Test
    fun `updateBackupPolicy with GLOBAL calls dao with GLOBAL`() = runTest {
        repository.updateBackupPolicy(1L, BackupPolicy.GLOBAL)
        assertEquals(1L to BackupPolicy.GLOBAL, fakeDao.lastPolicyUpdate)
    }

    @Test
    fun `updateBackupPolicy with ALWAYS_INCLUDE calls dao with ALWAYS_INCLUDE`() = runTest {
        repository.updateBackupPolicy(2L, BackupPolicy.ALWAYS_INCLUDE)
        assertEquals(2L to BackupPolicy.ALWAYS_INCLUDE, fakeDao.lastPolicyUpdate)
    }

    @Test
    fun `updateBackupPolicy with NEVER_INCLUDE calls dao with NEVER_INCLUDE`() = runTest {
        repository.updateBackupPolicy(3L, BackupPolicy.NEVER_INCLUDE)
        assertEquals(3L to BackupPolicy.NEVER_INCLUDE, fakeDao.lastPolicyUpdate)
    }

    // ── Fake ─────────────────────────────────────────────────────────────────

    private class FakeThreadDao : ThreadDao {
        var lastPolicyUpdate: Pair<Long, BackupPolicy>? = null

        override suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) {
            lastPolicyUpdate = threadId to policy
        }

        override fun observeAll(): Flow<List<ThreadEntity>> = emptyFlow()
        override suspend fun getById(threadId: Long): ThreadEntity? = null
        override fun observeById(threadId: Long): Flow<ThreadEntity?> = flowOf(null)
        override suspend fun insert(thread: ThreadEntity) {}
        override suspend fun insertAll(threads: List<ThreadEntity>) {}
        override suspend fun update(thread: ThreadEntity) {}
        override suspend fun delete(thread: ThreadEntity) {}
        override suspend fun getThreadsForBackup(): List<ThreadEntity> = emptyList()
        override suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity> = emptyList()
        override suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) {}
        override suspend fun updateLastMessagePreview(threadId: Long, preview: String) {}
        override suspend fun deleteAll() {}
    }
}
