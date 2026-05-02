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
    val reactions: List<Reaction> = emptyList()
)
