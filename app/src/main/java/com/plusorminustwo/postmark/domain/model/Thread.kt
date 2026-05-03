package com.plusorminustwo.postmark.domain.model

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
 */
data class Thread(
    val id: Long,
    val displayName: String,
    val address: String,
    val lastMessageAt: Long,
    val lastMessagePreview: String = "",
    val backupPolicy: BackupPolicy = BackupPolicy.GLOBAL,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    val notificationsEnabled: Boolean = true
)
