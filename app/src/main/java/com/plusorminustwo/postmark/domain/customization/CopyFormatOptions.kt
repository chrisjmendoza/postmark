package com.plusorminustwo.postmark.domain.customization

/**
 * Controls what the "Copy" action in a thread's selection bar puts on the clipboard.
 *
 * Every field defaults to the full transcript, so callers that don't care about the
 * user's preference (the readable ZIP export, which archives at full fidelity) can
 * keep using `CopyFormatOptions()`.
 *
 * Persisted by
 * [com.plusorminustwo.postmark.data.preferences.CopyFormatPreferenceRepository] and
 * consumed by [com.plusorminustwo.postmark.domain.formatter.ExportFormatter].
 *
 * @param includePhoneNumber  Show the other party's number in the header line. It only
 *                            ever appears there — message lines are labelled by name.
 * @param includeDates        Emit the date line (and the day dividers on a multi-day
 *                            selection).
 * @param includeTimestamps   Emit the "(3:04 PM)" clock time after each sender label.
 * @param includeReactions    Emit the "↩ You reacted ❤️" line under a reacted-to message.
 * @param includeAttachments  Emit the "[Photo]" / "[Attachment: …]" media line.
 */
data class CopyFormatOptions(
    val includePhoneNumber: Boolean = true,
    val includeDates: Boolean = true,
    val includeTimestamps: Boolean = true,
    val includeReactions: Boolean = true,
    val includeAttachments: Boolean = true
)
