package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.plusorminustwo.postmark.domain.model.Message

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
    val type: Int = 1
)

fun MessageEntity.toDomain() = Message(
    id = id,
    threadId = threadId,
    address = address,
    body = body,
    timestamp = timestamp,
    isSent = isSent,
    type = type
)

fun Message.toEntity() = MessageEntity(
    id = id,
    threadId = threadId,
    address = address,
    body = body,
    timestamp = timestamp,
    isSent = isSent,
    type = type
)
