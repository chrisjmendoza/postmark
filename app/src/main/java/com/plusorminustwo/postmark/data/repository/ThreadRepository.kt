package com.plusorminustwo.postmark.data.repository

import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import com.plusorminustwo.postmark.data.db.entity.toDomain
import com.plusorminustwo.postmark.data.db.entity.toEntity
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.model.encodeParticipantsJson
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
    /**
     * The user-facing thread list — spam threads are EXCLUDED (they live in the Spam
     * folder, see [observeSpam]). Every list surface (conversation list, search,
     * forward picker, export picker) observes through here, so a spam thread is hidden
     * everywhere at once. Aggregate consumers that legitimately want spam too (Stats)
     * read [ThreadDao.observeAll] directly.
     */
    fun observeAll(): Flow<List<Thread>> =
        dao.observeNonSpam().map { list -> list.map { it.toDomain() } }

    /** Spam threads only — backs the Spam folder screen. */
    fun observeSpam(): Flow<List<Thread>> =
        dao.observeSpam().map { list -> list.map { it.toDomain() } }

    fun observeById(threadId: Long): Flow<Thread?> =
        dao.observeById(threadId).map { it?.toDomain() }

    suspend fun getById(threadId: Long): Thread? =
        dao.getById(threadId)?.toDomain()

    /** One-shot snapshot of every thread. Used by the contact-name refresh pass. */
    suspend fun getAll(): List<Thread> =
        dao.getAll().map { it.toDomain() }

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

    /** Group threads only (roster stored). Used by the one-shot roster repair pass. */
    suspend fun getThreadsWithParticipants(): List<Thread> =
        dao.getThreadsWithParticipants().map { it.toDomain() }

    /** Repair write: replaces the roster + comma-joined display name for [threadId].
     *  Pass an empty list to demote a mis-classified group back to a 1:1 thread. */
    suspend fun updateRoster(threadId: Long, participants: List<String>, displayName: String) =
        dao.updateRoster(threadId, encodeParticipantsJson(participants), displayName)

    /** Updates ONLY the thread's displayName (contact-name refresh). Leaves user-set
     *  fields and the group roster untouched. */
    suspend fun updateDisplayName(threadId: Long, displayName: String) =
        dao.updateDisplayName(threadId, displayName)

    suspend fun updateLastMessageAt(threadId: Long, timestamp: Long) =
        dao.updateLastMessageAt(threadId, timestamp)

    suspend fun updateLastMessagePreview(threadId: Long, preview: String) =
        dao.updateLastMessagePreview(threadId, preview)

    suspend fun deleteAll() = dao.deleteAll()

    /** Returns true if the threads table has no rows. */
    suspend fun isEmpty(): Boolean = dao.count() == 0

    suspend fun updateNotificationsEnabled(threadId: Long, enabled: Boolean) =
        dao.updateNotificationsEnabled(threadId, enabled)

    /** Marks a thread as spam (hidden into the Spam folder + silenced) or restores it. */
    suspend fun updateSpam(threadId: Long, isSpam: Boolean) =
        dao.updateSpam(threadId, isSpam)

    /** Saves a Postmark-only nickname for the thread; pass null to clear it. */
    suspend fun setNickname(threadId: Long, nickname: String?) =
        dao.updateNickname(threadId, nickname)

    /** Saves a Postmark-only accent color (ARGB) for the thread; pass null to clear it. */
    suspend fun setAccentColor(threadId: Long, argb: Int?) =
        dao.updateAccentColor(threadId, argb)

    /** Saves a Postmark-only chat background id for the thread; pass null to clear it. */
    suspend fun setChatBackground(threadId: Long, backgroundId: String?) =
        dao.updateChatBackground(threadId, backgroundId)

    /** Saves a Postmark-only sent-bubble color (ARGB) for the thread; pass null to clear it. */
    suspend fun setSentColor(threadId: Long, argb: Int?) =
        dao.updateSentColor(threadId, argb)

    /** Number of threads whose chat-background override equals [id] — used to decide
     *  whether a custom image background is still referenced before deleting its file. */
    suspend fun countByChatBackground(id: String): Int =
        dao.countByChatBackground(id)
}
