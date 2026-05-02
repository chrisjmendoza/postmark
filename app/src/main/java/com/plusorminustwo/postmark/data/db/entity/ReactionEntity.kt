package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.plusorminustwo.postmark.domain.model.Reaction

/**
 * Room entity for a single emoji reaction on a message.
 *
 * Reactions are parsed from Apple iMessage reaction phrases during sync, or created
 * directly when the local user taps a reaction pill. Foreign-keyed to [MessageEntity]
 * with CASCADE delete. Indexed on `messageId` (for per-message lookups) and `emoji`
 * (for stats and search filtering).
 *
 * Maps 1-to-1 with [com.plusorminustwo.postmark.domain.model.Reaction].
 */
@Entity(
    tableName = "reactions",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId"), Index("emoji")]
)
data class ReactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val senderAddress: String,
    val emoji: String,
    val timestamp: Long,
    val rawText: String
)

fun ReactionEntity.toDomain() = Reaction(
    id = id,
    messageId = messageId,
    senderAddress = senderAddress,
    emoji = emoji,
    timestamp = timestamp,
    rawText = rawText
)

fun Reaction.toEntity() = ReactionEntity(
    id = id,
    messageId = messageId,
    senderAddress = senderAddress,
    emoji = emoji,
    timestamp = timestamp,
    rawText = rawText
)
