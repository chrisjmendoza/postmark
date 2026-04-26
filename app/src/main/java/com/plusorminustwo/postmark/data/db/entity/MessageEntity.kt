package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.plusorminustwo.postmark.domain.model.Message

const val DELIVERY_STATUS_NONE = 0
const val DELIVERY_STATUS_PENDING = 1
const val DELIVERY_STATUS_SENT = 2
const val DELIVERY_STATUS_DELIVERED = 3
const val DELIVERY_STATUS_FAILED = 4

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
    val deliveryStatus: Int = DELIVERY_STATUS_NONE
)

fun MessageEntity.toDomain() = Message(
    id = id,
    threadId = threadId,
    address = address,
    body = body,
    timestamp = timestamp,
    isSent = isSent,
    type = type,
    deliveryStatus = deliveryStatus
)

fun Message.toEntity() = MessageEntity(
    id = id,
    threadId = threadId,
    address = address,
    body = body,
    timestamp = timestamp,
    isSent = isSent,
    type = type,
    deliveryStatus = deliveryStatus
)
