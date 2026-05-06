package com.plusorminustwo.postmark.domain.model

import androidx.compose.runtime.Immutable

/**
 * Domain model for a single SMS/MMS message.
 *
 * @param id              System message ID from `content://sms`.
 * @param threadId        The thread this message belongs to.
 * @param address         Sender phone number (received) or the device's own number (sent).
 * @param body            Plain text content of the message.
 * @param timestamp       Epoch millis when the message was sent or received.
 * @param isSent          True when this device sent the message; false for received.
 * @param type            SMS type integer from the content provider (1 = inbox, 2 = sent, etc.).
 * @param deliveryStatus  Delivery state for sent messages — see `DELIVERY_STATUS_*` constants
 *                        in [com.plusorminustwo.postmark.data.db.entity.MessageEntity].
 * @param reactions       Apple-style emoji reactions attached to this message, parsed from
 *                        reaction phrases like "Liked \"hello\"" during sync.
 * @param isMms           True when this message came from `content://mms`; false for SMS.
 *                        IDs for MMS rows are offset by [MMS_ID_OFFSET] to avoid collisions.
 * @param attachmentUri   Content URI of the first MMS media part (e.g. `content://mms/part/42`),
 *                        or null for SMS and text-only MMS. Readable by the default SMS app.
 * @param mimeType        MIME type of the attachment (e.g. `image/jpeg`, `audio/mpeg`), or null.
 */
@Immutable
data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val isSent: Boolean,
    val type: Int,
    val deliveryStatus: Int = 0,
    val reactions: List<Reaction> = emptyList(),
    val isMms: Boolean = false,
    // MMS media attachment — null for SMS and text-only MMS.
    val attachmentUri: String? = null,
    val mimeType: String? = null,
    // False for incoming messages not yet viewed; drives the unread badge.
    val isRead: Boolean = true
)

/** Offset added to raw MMS `_id` values before storing in Room, preventing
 *  collision with SMS IDs (which top out around 100M on real devices). */
const val MMS_ID_OFFSET = 10_000_000_000L

/**
 * Human-readable preview text suitable for the conversation list.
 * Photo/video/audio messages show an emoji label instead of an empty string.
 */
val Message.previewText: String
    get() = when {
        body.isNotEmpty()                             -> body
        mimeType?.startsWith("image/") == true        -> "📷 Photo"
        mimeType?.startsWith("video/") == true        -> "🎥 Video"
        mimeType?.startsWith("audio/") == true        -> "🎵 Audio message"
        isMms                                         -> "[MMS]"
        else                                          -> body
    }
