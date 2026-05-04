package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.plusorminustwo.postmark.domain.model.Message

// Delivery status constants — stored as Int on MessageEntity.deliveryStatus
const val DELIVERY_STATUS_NONE = 0      // not yet tracked (older synced messages)
const val DELIVERY_STATUS_PENDING = 1   // sent to telephony, awaiting confirmation
const val DELIVERY_STATUS_SENT = 2      // telephony confirmed send
const val DELIVERY_STATUS_DELIVERED = 3 // recipient device acknowledged delivery
const val DELIVERY_STATUS_FAILED = 4    // delivery failed; shown with error indicator

/**
 * Room entity for a single SMS/MMS message.
 *
 * Foreign-keyed to [ThreadEntity] with CASCADE delete so messages are automatically
 * removed when their parent thread is deleted. Indexed on `threadId` (for per-thread
 * queries) and `timestamp` (for date-range queries and sorting).
 *
 * Maps 1-to-1 with [com.plusorminustwo.postmark.domain.model.Message]. Reactions are loaded
 * separately via [ReactionEntity] and assembled in the repository layer.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("threadId"), Index("timestamp")]
)
data class MessageEntity(
    @PrimaryKey val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val isSent: Boolean,
    val type: Int = 1,
    val deliveryStatus: Int = DELIVERY_STATUS_NONE,
    // True when this row was sourced from content://mms rather than content://sms.
    // IDs for MMS rows are offset by MMS_ID_OFFSET to prevent primary-key collisions.
    val isMms: Boolean = false,
    // Content URI pointing to the first media part of an MMS (e.g. content://mms/part/42).
    // Null for SMS rows and text-only MMS. Readable by the default SMS app role.
    val attachmentUri: String? = null,
    // MIME type of the attachment part (e.g. "image/jpeg", "audio/mpeg"). Null when no attachment.
    val mimeType: String? = null
)

fun MessageEntity.toDomain() = Message(
    id = id,
    threadId = threadId,
    address = address,
    body = body,
    timestamp = timestamp,
    isSent = isSent,
    type = type,
    deliveryStatus = deliveryStatus,
    isMms = isMms,
    attachmentUri = attachmentUri,
    mimeType = mimeType
)

fun Message.toEntity() = MessageEntity(
    id = id,
    threadId = threadId,
    address = address,
    body = body,
    timestamp = timestamp,
    isSent = isSent,
    type = type,
    deliveryStatus = deliveryStatus,
    isMms = isMms,
    attachmentUri = attachmentUri,
    mimeType = mimeType
)
