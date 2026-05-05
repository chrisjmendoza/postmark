package com.plusorminustwo.postmark.domain.model

import androidx.compose.runtime.Immutable

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
    val isRead: Boolean = true
)
