package com.plusorminustwo.postmark.domain.model

import androidx.compose.runtime.Immutable

/**
 * Domain model representing a single SMS/MMS conversation thread.
 *
 * @param id                  System thread ID from the `content://sms` ContentProvider.
 * @param displayName         Contact name or formatted phone number shown in the conversation list.
 * @param address             Raw phone number. Used as a stable color seed for the letter avatar
 *                            so the color doesn't change if the contact name changes.
 * @param lastMessageAt       Epoch millis of the most recent message in this thread.
 * @param lastMessagePreview  Snippet of the last message body shown in the conversation list.
 * @param backupPolicy        Whether this thread follows the global backup rule or has an override.
 * @param isMuted                  When true, incoming messages do not trigger notifications.
 * @param isPinned                 When true, this thread floats above unpinned threads in the list.
 * @param notificationsEnabled     When false, incoming messages post no notification at all
 *                                 (stronger than mute — use to fully silence a number/thread).
 * @param nickname                 Postmark-only display alias set by the user. When non-null
 *                                 this overrides [displayName] everywhere in the UI. Never
 *                                 written back to the system Contacts database.
 * @param participants             Full roster of addresses for a group MMS thread (empty for
 *                                 an ordinary 1:1 thread, including [address]). [displayName]
 *                                 is already comma-joined from these at thread-creation time,
 *                                 so most UI does not need to read this directly — it exists
 *                                 for logic that needs to know "is this a group thread?"
 *                                 (e.g. gating group-aware sending, which isn't implemented yet).
 * @param accentColorArgb          Postmark-only contact color (ARGB) — the contact's
 *                                 avatar and their received-message bubbles. Null means
 *                                 use the app default (hash-derived avatar color, neutral
 *                                 received bubble).
 * @param chatBackgroundId         Postmark-only per-thread chat background id. Null falls
 *                                 back to the global default background (also null = none).
 * @param sentColorArgb            Postmark-only sent-bubble color (ARGB), independent of
 *                                 [accentColorArgb]. Null means use the app default
 *                                 (primaryContainer).
 */
// @Immutable: the participants List makes Thread inferred-unstable, which made every
// visible ThreadRow unskippable — all rows recomposed on each threads/unreadCounts
// emission. All fields are read-only and never mutated after construction.
@Immutable
data class Thread(
    val id: Long,
    val displayName: String,
    val address: String,
    val lastMessageAt: Long,
    val lastMessagePreview: String = "",
    val backupPolicy: BackupPolicy = BackupPolicy.GLOBAL,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    val notificationsEnabled: Boolean = true,
    // Postmark-only alias — null means fall back to displayName in the UI.
    val nickname: String? = null,
    val participants: List<String> = emptyList(),
    // Postmark-only contact color (ARGB) — avatar + received bubbles. Null means default.
    val accentColorArgb: Int? = null,
    // Postmark-only chat background id. Null falls back to the global default.
    val chatBackgroundId: String? = null,
    // Postmark-only sent-bubble color (ARGB), independent of accentColorArgb. Null = default.
    val sentColorArgb: Int? = null
)
