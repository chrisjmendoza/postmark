package com.plusorminustwo.postmark.domain.model

data class Reaction(
    val id: Long,
    val messageId: Long,
    val senderAddress: String,
    val emoji: String,
    val timestamp: Long,
    val rawText: String
)
