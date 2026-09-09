package com.plusorminustwo.postmark.domain.formatter

import com.plusorminustwo.postmark.domain.customization.CopyFormatOptions
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats a list of messages as human-readable plain text for clipboard export.
 *
 * Produces a header with the thread display name followed by each message body
 * in chronological order. Reaction summaries are appended inline if present.
 * Day separator lines are inserted between messages on different days when the
 * conversation spans more than one calendar day.
 *
 * The phone number appears in the header line and nowhere else — an unnamed thread
 * labels its incoming messages [UNNAMED_SENDER] rather than repeating the number on
 * every line. [CopyFormatOptions] can drop the number (and the other optional parts)
 * entirely.
 */
object ExportFormatter {

    /** Sender label for a thread with no contact name — the number stays in the header. */
    const val UNNAMED_SENDER = "Them"

    // SimpleDateFormat is not thread-safe, and this formatter now runs on worker
    // threads (readable export) as well as Main (Copy) — instances are created per
    // call rather than shared. Cost is negligible next to iterating the messages.
    private fun dayFormatter() = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    private fun timeFormatter() = SimpleDateFormat("h:mm a", Locale.getDefault())

    /**
     * Renders [messages] as a plain-text transcript suitable for sharing.
     *
     * @param messages The messages to include, in chronological order.
     * @param threadDisplayName Display name of the other party. A name with no letters
     *   (or a blank one) is treated as "no name known": the number goes in the header
     *   and message lines are labelled [UNNAMED_SENDER].
     * @param threadAddress The other party's phone number — the header is the only place
     *   it appears ("Conversation with Sarah (206) 555-1234"), so the number can be
     *   verified from the paste without repeating on every message line.
     * @param options Which optional parts of the transcript to emit. Defaults to all of
     *   them; the Copy action passes the user's saved preference.
     * @param attachmentNote Optional per-message line describing the message's media
     *   (the readable export passes "[Attachment: media/Sarah/2026-05-01_1432.jpg]").
     *   Defaults to [mediaPlaceholder], so plain Copy shows "[Photo]" instead of a bare
     *   timestamp. Ignored when [CopyFormatOptions.includeAttachments] is false.
     * @return A formatted string, or an empty string if [messages] is empty.
     */
    fun formatForCopy(
        messages: List<Message>,
        threadDisplayName: String,
        threadAddress: String = "",
        options: CopyFormatOptions = CopyFormatOptions(),
        attachmentNote: (Message) -> String? = ::mediaPlaceholder
    ): String {
        if (messages.isEmpty()) return ""

        val dayFormatter = dayFormatter()
        val timeFormatter = timeFormatter()
        val sb = StringBuilder()

        // A display name with no letters is the phone number itself (or a formatted
        // version of it), not a name — the header carries the number instead.
        val isNamed = threadDisplayName.any { it.isLetter() }
        val senderName = if (isNamed) threadDisplayName else UNNAMED_SENDER
        val number = if (options.includePhoneNumber) formatPhoneNumber(threadAddress) else ""
        sb.append(
            when {
                isNamed && number.isNotBlank() -> "Conversation with $threadDisplayName $number"
                isNamed -> "Conversation with $threadDisplayName"
                number.isNotBlank() -> "Conversation with $number"
                else -> "Conversation"
            }
        ).append('\n')

        val spansMultipleDays = spansMultipleDays(messages)
        var lastDayLabel: String? = null

        if (options.includeDates && !spansMultipleDays) {
            sb.append("${dayFormatter.format(Date(messages.first().timestamp))}\n")
        }

        messages.forEach { msg ->
            if (options.includeDates && spansMultipleDays) {
                val dayLabel = dayFormatter.format(Date(msg.timestamp))
                if (dayLabel != lastDayLabel) {
                    sb.append("$dayLabel\n")
                    sb.append("────────────────────────\n")
                    lastDayLabel = dayLabel
                }
            }

            val senderLabel = if (msg.isSent) "You" else senderName
            if (options.includeTimestamps) {
                sb.append("$senderLabel (${timeFormatter.format(Date(msg.timestamp))})\n")
            } else {
                sb.append("$senderLabel\n")
            }

            val note = if (options.includeAttachments) attachmentNote(msg) else null
            if (msg.body.isNotBlank()) sb.append("${msg.body}\n")
            note?.let { sb.append("$it\n") }

            if (options.includeReactions) reactionLine(msg, senderName)?.let { sb.append(it) }
            sb.append("\n")
        }

        return sb.toString().trimEnd()
    }

    /**
     * The "  ↩ You reacted ❤️, Sarah reacted 👍" line for [msg], or null when nothing
     * reacted to it. Grouped by reactor rather than by emoji so the paste says who did
     * what — reactions the local user added are stored under [SELF_ADDRESS], which is
     * what makes them read as "You".
     */
    private fun reactionLine(msg: Message, senderName: String): String? {
        if (msg.reactions.isEmpty()) return null
        val byReactor = msg.reactions
            .groupBy { if (it.senderAddress == SELF_ADDRESS) "You" else senderName }
            .map { (who, reactions) ->
                "$who reacted ${reactions.map { it.emoji }.distinct().joinToString(" ")}"
            }
        return "  ↩ ${byReactor.joinToString(", ")}\n"
    }

    /**
     * Bracketed media description for a message's attachments, or null when it has
     * none: "[Photo]", "[2 photos]", "[Video]", "[Audio message]", "[Photo, video]".
     * The default [formatForCopy] note — media-only messages used to copy as a
     * sender line over nothing but a blank.
     */
    fun mediaPlaceholder(msg: Message): String? {
        if (msg.attachments.isEmpty()) return null
        val counts = msg.attachments
            .groupingBy { att ->
                when {
                    att.mimeType.startsWith("image/", ignoreCase = true) -> "photo"
                    att.mimeType.startsWith("video/", ignoreCase = true) -> "video"
                    att.mimeType.startsWith("audio/", ignoreCase = true) -> "audio message"
                    else -> "attachment"
                }
            }
            .eachCount()
        val described = counts.entries.joinToString(", ") { (kind, n) ->
            if (n == 1) kind else "$n ${kind}s"
        }
        return "[${described.replaceFirstChar { it.uppercase() }}]"
    }

    private fun spansMultipleDays(messages: List<Message>): Boolean {
        if (messages.size < 2) return false
        val dayFormatter = dayFormatter()
        val firstDay = dayFormatter.format(Date(messages.first().timestamp))
        val lastDay = dayFormatter.format(Date(messages.last().timestamp))
        return firstDay != lastDay
    }
}
