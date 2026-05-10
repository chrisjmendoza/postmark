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

    @Query("SELECT * FROM threads ORDER BY isPinned DESC, lastMessageAt DESC")
    fun observeAll(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE id = :threadId")
    suspend fun getById(threadId: Long): ThreadEntity?

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

    @Query("SELECT notificationsEnabled FROM threads WHERE address = :address LIMIT 1")
    suspend fun isNotificationsEnabledByAddress(address: String): Boolean?

    @Query("SELECT * FROM threads WHERE backupPolicy != 'NEVER_INCLUDE'")
    suspend fun getThreadsForBackup(): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE backupPolicy = :policy")
    suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity>

    @Query("UPDATE threads SET lastMessageAt = :timestamp WHERE id = :threadId")
    suspend fun updateLastMessageAt(threadId: Long, timestamp: Long)

    @Query("UPDATE threads SET lastMessagePreview = :preview WHERE id = :threadId")
    suspend fun updateLastMessagePreview(threadId: Long, preview: String)

    @Query("SELECT isMuted FROM threads WHERE address = :address LIMIT 1")
    suspend fun isMutedByAddress(address: String): Boolean?

    @Query("SELECT displayName FROM threads WHERE address = :address LIMIT 1")
    suspend fun getDisplayNameByAddress(address: String): String?

    /** Saves a Postmark-only nickname for the thread; pass null to clear it. */
    @Query("UPDATE threads SET nickname = :nickname WHERE id = :threadId")
    suspend fun updateNickname(threadId: Long, nickname: String?)

    @Query("DELETE FROM threads")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM threads")
    suspend fun count(): Int
}
