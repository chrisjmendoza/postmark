package com.plusorminustwo.postmark.domain.model

data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val isSent: Boolean,
    val type: Int,
    val reactions: List<Reaction> = emptyList()
)
