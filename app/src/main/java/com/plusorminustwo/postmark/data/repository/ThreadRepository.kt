package com.plusorminustwo.postmark.data.repository

import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.toDomain
import com.plusorminustwo.postmark.data.db.entity.toEntity
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Thread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThreadRepository @Inject constructor(
    private val dao: ThreadDao
) {
    fun observeAll(): Flow<List<Thread>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeById(threadId: Long): Flow<Thread?> =
        dao.observeById(threadId).map { it?.toDomain() }

    suspend fun getById(threadId: Long): Thread? =
        dao.getById(threadId)?.toDomain()

    suspend fun upsert(thread: Thread) = dao.insert(thread.toEntity())

    suspend fun upsertAll(threads: List<Thread>) =
        dao.insertAll(threads.map { it.toEntity() })

    suspend fun delete(thread: Thread) = dao.delete(thread.toEntity())

    suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) =
        dao.updateBackupPolicy(threadId, policy)

    suspend fun updateMuted(threadId: Long, isMuted: Boolean) =
        dao.updateMuted(threadId, isMuted)

    suspend fun getThreadsForBackup(): List<Thread> =
        dao.getThreadsForBackup().map { it.toDomain() }

    suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) =
        dao.updateLastMessageAt(threadId, timestamp)

    suspend fun updateLastMessagePreview(threadId: Long, preview: String) =
        dao.updateLastMessagePreview(threadId, preview)

    suspend fun deleteAll() = dao.deleteAll()
}
