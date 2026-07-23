package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.decodeAttachmentsJson
import com.plusorminustwo.postmark.domain.model.encodeAttachmentsJson

// Delivery status constants — stored as Int on MessageEntity.deliveryStatus
const val DELIVERY_STATUS_NONE = 0      // not yet tracked (older synced messages)
const val DELIVERY_STATUS_PENDING = 1   // sent to telephony, awaiting confirmation
const val DELIVERY_STATUS_SENT = 2      // telephony confirmed send
const val DELIVERY_STATUS_DELIVERED = 3 // recipient device acknowledged delivery
const val DELIVERY_STATUS_FAILED = 4    // delivery failed; shown with error indicator
const val DELIVERY_STATUS_QUEUED = 5    // send deferred (no service / radio off); the offline
                                        // send queue flushes these in timestamp order when
                                        // service returns. A VALUE in this Int column — no schema
                                        // change; the QUEUED rows themselves ARE the queue.

/**
 * Room entity for a single SMS/MMS message.
 *
 * Foreign-keyed to [ThreadEntity] with CASCADE delete so messages are automatically
 * removed when their parent thread is deleted. Indexed on `threadId` (for per-thread
 * queries) and `timestamp` (for date-range queries and sorting).
 *
 * Maps 1-to-1 with [com.plusorminustwo.postmark.domain.model.Message]. Reactions are loaded
 * separately via [ReactionEntity] and assembled in the repository layer.
 *
 * Index rationale (added v16): (threadId, timestamp) returns per-thread queries in
 * index order without a sort pass (its threadId prefix also covers the FK and all
 * threadId-only lookups); (isRead, threadId) covers the unread-badge aggregation;
 * isMms covers the sync watermark MIN/MAX queries; isStarred the starred gallery.
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
    indices = [
        Index("threadId", "timestamp"),
        Index("timestamp"),
        Index("isRead", "threadId"),
        Index("isMms"),
        Index("isStarred")
    ]
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
    // Content URI of the FIRST media part (e.g. content://mms/part/42). Null for SMS rows
    // and text-only MMS. Kept in sync with attachmentsJson so single-attachment queries
    // (observeMediaMessages) and pre-v12 rows keep working without a data migration.
    val attachmentUri: String? = null,
    // MIME type of the first attachment part. Null when no attachment.
    val mimeType: String? = null,
    // JSON array of ALL media parts ([{"uri":...,"mimeType":...},...]) — see
    // encodeAttachmentsJson(). Null for SMS, text-only MMS, and rows written before v12
    // (those fall back to the singular attachmentUri/mimeType pair in toDomain()).
    val attachmentsJson: String? = null,
    // False for received messages that have not yet been viewed (drives unread badge).
    // Defaults to true so existing synced rows never appear as unread after an upgrade.
    val isRead: Boolean = true,
    // Postmark-only favorite flag — surfaces this message's images in the global
    // Starred Images gallery. Never written back to the system SMS/MMS provider.
    val isStarred: Boolean = false,
    // Postmark-only pin flag (Discord-style) — surfaces this message in the per-thread
    // Pinned messages panel. Distinct from the image-only isStarred and from the
    // thread-level ThreadEntity.isPinned. Never written back to the SMS/MMS provider.
    val isPinned: Boolean = false
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
    // Rows written before schema v12 have a null attachmentsJson — fall back to the
    // singular column pair so their (single) attachment stays visible.
    attachments = decodeAttachmentsJson(attachmentsJson).ifEmpty {
        attachmentUri?.let { listOf(MessageAttachment(it, mimeType ?: "application/octet-stream")) }
            ?: emptyList()
    },
    isRead = isRead,
    isStarred = isStarred,
    isPinned = isPinned
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
    mimeType = mimeType,
    attachmentsJson = encodeAttachmentsJson(attachments),
    isRead = isRead,
    isStarred = isStarred,
    isPinned = isPinned
)
