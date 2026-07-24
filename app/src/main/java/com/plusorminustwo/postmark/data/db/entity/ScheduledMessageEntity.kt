package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.plusorminustwo.postmark.domain.scheduled.ScheduledMessage

/**
 * Room entity for a text-only SMS parked to send at a future time ("Schedule send").
 *
 * Deliberately a SEPARATE table, NOT a column on `messages`: a scheduled send must never
 * enter the telephony provider or the `messages` table before it fires. Keeping it out of
 * `messages` means sync dedup/healing can never mistake it for a real row, and no
 * thread/search/stats/backup query can leak a not-yet-sent message. When the
 * [com.plusorminustwo.postmark.service.scheduled.ScheduledSendWorker] fires it deletes this
 * row and hands the text to the ordinary optimistic-insert SMS send path — the message only
 * ever reaches `messages` (and the provider) as a normal send at that moment.
 *
 * v1 scope: text-only SMS (no attachments/MMS), so there is no attachment column here.
 * Not carried through backup/restore in v1 (a pending scheduled send is transient device
 * state, not history) — deferred.
 */
@Entity(tableName = "scheduled_messages")
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val address: String,
    val body: String,
    val scheduledAt: Long,
    val createdAt: Long,
)

fun ScheduledMessageEntity.toDomain() = ScheduledMessage(
    id = id,
    threadId = threadId,
    address = address,
    body = body,
    scheduledAt = scheduledAt,
    createdAt = createdAt,
)

fun ScheduledMessage.toEntity() = ScheduledMessageEntity(
    id = id,
    threadId = threadId,
    address = address,
    body = body,
    scheduledAt = scheduledAt,
    createdAt = createdAt,
)
