package com.plusorminustwo.postmark.data.db.dao

import androidx.room.*
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `threads` table.
 *
 * Provides CRUD operations and targeted update queries for [ThreadEntity] rows.
 * Key design note: use [insertIgnore] / [insertAllIgnore] when syncing from the
 * content provider to preserve user-set fields ([ThreadEntity.isMuted],
 * [ThreadEntity.isPinned], [ThreadEntity.notificationsEnabled]) that a full
 * REPLACE strategy would silently overwrite.
 */
@Dao
interface ThreadDao {

    /* Every thread, spam included. Used by Stats (aggregate analytics count spam too) and
     * the one-shot repair/refresh passes. The user-facing list surfaces use [observeNonSpam]. */
    @Query("SELECT * FROM threads ORDER BY isPinned DESC, lastMessageAt DESC")
    fun observeAll(): Flow<List<ThreadEntity>>

    /* Non-spam threads only — the conversation list and every other list surface (search,
     * forward picker, export picker) observe through this so spam is hidden everywhere. */
    @Query("SELECT * FROM threads WHERE isSpam = 0 ORDER BY isPinned DESC, lastMessageAt DESC")
    fun observeNonSpam(): Flow<List<ThreadEntity>>

    /* Spam threads only — backs the Spam folder screen. */
    @Query("SELECT * FROM threads WHERE isSpam = 1 ORDER BY lastMessageAt DESC")
    fun observeSpam(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE id = :threadId")
    suspend fun getById(threadId: Long): ThreadEntity?

    /* One-shot snapshot of every thread. Feeds the contact-name refresh pass, which
     * re-resolves each 1:1 thread's contact name and updates a stale displayName. */
    @Query("SELECT * FROM threads")
    suspend fun getAll(): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE id = :threadId")
    fun observeById(threadId: Long): Flow<ThreadEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(thread: ThreadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(threads: List<ThreadEntity>)

    /* Sync-safe insert: creates the thread if it does not exist, leaves it
     * untouched if it does. Preserves user settings (isPinned, isMuted,
     * notificationsEnabled) that a REPLACE strategy would silently overwrite. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(thread: ThreadEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(threads: List<ThreadEntity>)

    @Update
    suspend fun update(thread: ThreadEntity)

    @Delete
    suspend fun delete(thread: ThreadEntity)

    @Query("UPDATE threads SET backupPolicy = :policy WHERE id = :threadId")
    suspend fun updateBackupPolicy(threadId: Long, policy: BackupPolicy)

    @Query("UPDATE threads SET isMuted = :isMuted WHERE id = :threadId")
    suspend fun updateMuted(threadId: Long, isMuted: Boolean)

    @Query("UPDATE threads SET isPinned = :isPinned WHERE id = :threadId")
    suspend fun updatePinned(threadId: Long, isPinned: Boolean)

    @Query("UPDATE threads SET notificationsEnabled = :enabled WHERE id = :threadId")
    suspend fun updateNotificationsEnabled(threadId: Long, enabled: Boolean)

    @Query("UPDATE threads SET isSpam = :isSpam WHERE id = :threadId")
    suspend fun updateSpam(threadId: Long, isSpam: Boolean)

    @Query("SELECT * FROM threads WHERE backupPolicy != 'NEVER_INCLUDE'")
    suspend fun getThreadsForBackup(): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE backupPolicy = :policy")
    suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity>

    /* Group threads only: participantsJson is non-null exactly when a >1 roster was
     * stored. Feeds the one-shot roster repair pass (GROUP_MESSAGING_SPEC §1.4). */
    @Query("SELECT * FROM threads WHERE participantsJson IS NOT NULL")
    suspend fun getThreadsWithParticipants(): List<ThreadEntity>

    /* Roster repair write: replaces the stored roster and its comma-joined display name
     * in one statement, leaving user-set fields (pin/mute/colors) untouched. */
    @Query("UPDATE threads SET participantsJson = :participantsJson, displayName = :displayName WHERE id = :threadId")
    suspend fun updateRoster(threadId: Long, participantsJson: String?, displayName: String)

    /* Contact-name refresh write: updates ONLY displayName (docs/TODO.md Tier 2). Never
     * touches user-set fields (isPinned/isMuted/notificationsEnabled/nickname/colors) or
     * the group roster (participantsJson), so it's safe to run over synced threads. */
    @Query("UPDATE threads SET displayName = :displayName WHERE id = :threadId")
    suspend fun updateDisplayName(threadId: Long, displayName: String)

    @Query("UPDATE threads SET lastMessageAt = :timestamp WHERE id = :threadId")
    suspend fun updateLastMessageAt(threadId: Long, timestamp: Long)

    @Query("UPDATE threads SET lastMessagePreview = :preview WHERE id = :threadId")
    suspend fun updateLastMessagePreview(threadId: Long, preview: String)

    /** Saves a Postmark-only nickname for the thread; pass null to clear it. */
    @Query("UPDATE threads SET nickname = :nickname WHERE id = :threadId")
    suspend fun updateNickname(threadId: Long, nickname: String?)

    /** Saves a Postmark-only accent color (ARGB) for the thread; pass null to clear it. */
    @Query("UPDATE threads SET accentColorArgb = :argb WHERE id = :threadId")
    suspend fun updateAccentColor(threadId: Long, argb: Int?)

    /** Saves a Postmark-only chat background id for the thread; pass null to clear it. */
    @Query("UPDATE threads SET chatBackgroundId = :backgroundId WHERE id = :threadId")
    suspend fun updateChatBackground(threadId: Long, backgroundId: String?)

    /** Saves a Postmark-only sent-bubble color (ARGB) for the thread; pass null to clear it. */
    @Query("UPDATE threads SET sentColorArgb = :argb WHERE id = :threadId")
    suspend fun updateSentColor(threadId: Long, argb: Int?)

    /** Number of threads whose per-thread chat-background override equals [id]. Feeds the
     *  orphan-cleanup decision for custom image backgrounds (Phase J). */
    @Query("SELECT COUNT(*) FROM threads WHERE chatBackgroundId = :id")
    suspend fun countByChatBackground(id: String): Int

    @Query("DELETE FROM threads")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM threads")
    suspend fun count(): Int
}
