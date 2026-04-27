package com.plusorminustwo.postmark.domain.model

import androidx.compose.runtime.Immutable

/** Sentinel sender address used for reactions added by the local user. */
const val SELF_ADDRESS = "self"

@Immutable
data class Reaction(
    val id: Long,
    val messageId: Long,
    val senderAddress: String,
    val emoji: String,
    val timestamp: Long,
    val rawText: String
)
