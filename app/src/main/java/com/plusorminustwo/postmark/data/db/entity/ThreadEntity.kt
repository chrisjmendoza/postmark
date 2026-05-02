package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Thread

@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val id: Long,
    val displayName: String,
    val address: String,
    val lastMessageAt: Long,
    val lastMessagePreview: String = "",
    val backupPolicy: BackupPolicy = BackupPolicy.GLOBAL,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false
)

fun ThreadEntity.toDomain() = Thread(
    id = id,
    displayName = displayName,
    address = address,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    backupPolicy = backupPolicy,
    isMuted = isMuted,
    isPinned = isPinned
)

fun Thread.toEntity() = ThreadEntity(
    id = id,
    displayName = displayName,
    address = address,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    backupPolicy = backupPolicy,
    isMuted = isMuted,
    isPinned = isPinned
)
