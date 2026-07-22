package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.model.decodeParticipantsJson
import com.plusorminustwo.postmark.domain.model.encodeParticipantsJson

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
    val notificationsEnabled: Boolean = true,
    // Postmark-only display alias — null means use displayName everywhere in the UI.
    val nickname: String? = null,
    // JSON array of group MMS participant addresses. Null for ordinary 1:1 threads.
    val participantsJson: String? = null,
    // Postmark-only contact color (ARGB) — avatar + received-bubble fill. Null means
    // use the default (LetterAvatar hash color / neutral surfaceVariant bubble).
    val accentColorArgb: Int? = null,
    // Postmark-only per-thread chat background id. Null falls back to the global default.
    val chatBackgroundId: String? = null,
    // Postmark-only sent-bubble fill (ARGB), independent of accentColorArgb. Null means
    // use the default primaryContainer.
    val sentColorArgb: Int? = null,
    // When true the thread is hidden from the main conversation list (and every other
    // list surface) into the Spam folder, and posts no incoming notifications.
    val isSpam: Boolean = false
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
    notificationsEnabled = notificationsEnabled,
    nickname = nickname,
    participants = decodeParticipantsJson(participantsJson),
    accentColorArgb = accentColorArgb,
    chatBackgroundId = chatBackgroundId,
    sentColorArgb = sentColorArgb,
    isSpam = isSpam
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
    notificationsEnabled = notificationsEnabled,
    nickname = nickname,
    participantsJson = encodeParticipantsJson(participants),
    accentColorArgb = accentColorArgb,
    chatBackgroundId = chatBackgroundId,
    sentColorArgb = sentColorArgb,
    isSpam = isSpam
)
