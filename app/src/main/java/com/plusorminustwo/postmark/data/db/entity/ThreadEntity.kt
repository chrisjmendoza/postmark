package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Thread

/**
 * Room entity for a conversation thread.
 *
 * Maps 1-to-1 with [com.plusorminustwo.postmark.domain.model.Thread]. Use [toDomain] to convert
 * for use in the UI layer, and [toEntity] to persist a domain object back to Room.
 */
@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val id: Long,
    val displayName: String,
    val address: String,
    val lastMessageAt: Long,
    val lastMessagePreview: String = "",
    val backupPolicy: BackupPolicy = BackupPolicy.GLOBAL,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    // When false the thread is fully silenced — no notification is posted for incoming messages.
    val notificationsEnabled: Boolean = true
)

/**
 * Converts a [ThreadEntity] (database row) to a [Thread] domain model.
 * Called by repository layer before returning data to ViewModels.
 */
fun ThreadEntity.toDomain() = Thread(
    id = id,
    displayName = displayName,
    address = address,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    backupPolicy = backupPolicy,
    isMuted = isMuted,
    isPinned = isPinned,
    notificationsEnabled = notificationsEnabled
)

/**
 * Converts a [Thread] domain model to a [ThreadEntity] ready for Room insertion.
 * Called by repository layer when persisting updates.
 */
fun Thread.toEntity() = ThreadEntity(
    id = id,
    displayName = displayName,
    address = address,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    backupPolicy = backupPolicy,
    isMuted = isMuted,
    isPinned = isPinned,
    notificationsEnabled = notificationsEnabled
)
