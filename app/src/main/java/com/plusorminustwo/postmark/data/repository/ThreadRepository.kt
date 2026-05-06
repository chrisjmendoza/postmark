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

/**
 * Repository for [Thread] domain objects backed by [ThreadDao].
 *
 * Provides both reactive [Flow] observers for live UI and one-shot suspend functions
 * for writes. The [insertIgnore] / [insertIgnoreAll] variants are used during sync
 * to preserve user-set fields (pinned, muted, notifications) that a full REPLACE
 * would overwrite.
 */
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

    /* Sync-safe variants: create thread if absent, preserve user settings if present. */
    suspend fun insertIgnore(thread: Thread) = dao.insertIgnore(thread.toEntity())
    suspend fun insertIgnoreAll(threads: List<Thread>) =
        dao.insertAllIgnore(threads.map { it.toEntity() })

    suspend fun delete(thread: Thread) = dao.delete(thread.toEntity())

    suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy) =
        dao.updateBackupPolicy(threadId, policy)

    suspend fun updateMuted(threadId: Long, isMuted: Boolean) =
        dao.updateMuted(threadId, isMuted)


    suspend fun updatePinned(threadId: Long, isPinned: Boolean) =
        dao.updatePinned(threadId, isPinned)


    suspend fun getThreadsForBackup(): List<Thread> =
        dao.getThreadsForBackup().map { it.toDomain() }

    suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) =
        dao.updateLastMessageAt(threadId, timestamp)

    suspend fun updateLastMessagePreview(threadId: Long, preview: String) =
        dao.updateLastMessagePreview(threadId, preview)

    suspend fun deleteAll() = dao.deleteAll()

    /** Returns true if the threads table has no rows. */
    suspend fun isEmpty(): Boolean = dao.count() == 0

    /** Returns true if the thread with [address] has notifications muted, false otherwise. */
    suspend fun isMutedByAddress(address: String): Boolean =
        dao.isMutedByAddress(address) ?: false

    /** Returns true if the thread with [address] has notifications enabled (default), false if
     *  the user has fully disabled notifications for that number. */
    suspend fun isNotificationsEnabledByAddress(address: String): Boolean =
        dao.isNotificationsEnabledByAddress(address) ?: true

    suspend fun updateNotificationsEnabled(threadId: Long, enabled: Boolean) =
        dao.updateNotificationsEnabled(threadId, enabled)

    // Looks up a stored display name by raw phone address; returns null when the thread
    // isn't in Room yet (e.g. before first sync completes).
    suspend fun getDisplayNameByAddress(address: String): String? =
        dao.getDisplayNameByAddress(address)
}
