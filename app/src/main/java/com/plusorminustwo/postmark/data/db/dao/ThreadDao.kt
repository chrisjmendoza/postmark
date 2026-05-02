package com.plusorminustwo.postmark.data.db.dao

import androidx.room.*
import com.plusorminustwo.postmark.data.db.entity.ThreadEntity
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM threads WHERE backupPolicy != 'NEVER_INCLUDE'")
    suspend fun getThreadsForBackup(): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE backupPolicy = :policy")
    suspend fun getThreadsByPolicy(policy: BackupPolicy): List<ThreadEntity>

    @Query("UPDATE threads SET lastMessageAt = :timestamp WHERE id = :threadId")
    suspend fun updateLastMessageAt(threadId: Long, timestamp: Long)

    @Query("UPDATE threads SET lastMessagePreview = :preview WHERE id = :threadId")
    suspend fun updateLastMessagePreview(threadId: Long, preview: String)

    @Query("DELETE FROM threads")
    suspend fun deleteAll()
}
