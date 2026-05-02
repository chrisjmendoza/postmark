package com.plusorminustwo.postmark.domain.model

import androidx.compose.runtime.Immutable

/** Sentinel sender address used for reactions added by the local user. */
const val SELF_ADDRESS = "self"

/**
 * Represents a single emoji reaction on a message.
 *
 * Apple iMessage reaction phrases (e.g. "Liked \"hello\"") are parsed into [Reaction] objects
 * by the Apple reaction parser during sync. Reactions tapped directly in the app's emoji picker
 * are also stored as [Reaction] objects with [senderAddress] == [SELF_ADDRESS].
 *
 * @param id             Auto-generated Room primary key.
 * @param messageId      The message this reaction is attached to.
 * @param senderAddress  Phone number of the reactor, or [SELF_ADDRESS] for the local user.
 * @param emoji          Single emoji character representing the reaction (e.g. "❤️").
 * @param timestamp      Epoch millis when the reaction was applied.
 * @param rawText        The original SMS phrase this was parsed from (empty for local taps).
 */
@Immutable
data class Reaction(
    val id: Long,
    val messageId: Long,
    val senderAddress: String,
    val emoji: String,
    val timestamp: Long,
    val rawText: String
)
