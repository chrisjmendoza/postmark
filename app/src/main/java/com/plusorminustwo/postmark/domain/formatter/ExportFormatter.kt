package com.plusorminustwo.postmark.domain.formatter

import com.plusorminustwo.postmark.domain.model.Message
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
 */
object ExportFormatter {

    // SimpleDateFormat is not thread-safe, and this formatter now runs on worker
    // threads (readable export) as well as Main (Copy) — instances are created per
    // call rather than shared. Cost is negligible next to iterating the messages.
    private fun dayFormatter() = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    private fun timeFormatter() = SimpleDateFormat("h:mm a", Locale.getDefault())

    /**
     * Renders [messages] as a plain-text transcript suitable for sharing.
     *
     * @param messages The messages to include, in chronological order.
     * @param threadDisplayName Display name of the other party (contact or phone number).
     * @param ownAddress The user's own phone number (used to label sent messages as "You").
     * @param threadAddress The other party's phone number — included in the header next
     *   to the name ("Conversation with Sarah (206) 555-1234") so the number can be
     *   verified from the paste alone. Skipped when it would just repeat the name
     *   (unknown contacts whose display name IS the number).
     * @param attachmentNote Optional per-message line describing the message's media
     *   (the readable export passes "[Attachment: media/Sarah/2026-05-01_1432.jpg]").
     *   When it returns non-null for a message with a blank body, the empty body line
     *   is dropped so photo-only messages don't export as blank lines.
     * @return A formatted string, or an empty string if [messages] is empty.
     */
    fun formatForCopy(
        messages: List<Message>,
        threadDisplayName: String,
        ownAddress: String,
        threadAddress: String = "",
        attachmentNote: (Message) -> String? = { null }
    ): String {
        if (messages.isEmpty()) return ""

        val dayFormatter = dayFormatter()
        val timeFormatter = timeFormatter()
        val sb = StringBuilder()
        val formattedNumber = if (threadAddress.isBlank()) "" else formatPhoneNumber(threadAddress)
        val headerNumber =
            if (formattedNumber.isEmpty() ||
                formattedNumber == threadDisplayName ||
                threadAddress == threadDisplayName
            ) "" else " $formattedNumber"
        sb.append("Conversation with $threadDisplayName$headerNumber\n")

        val spansMultipleDays = spansMultipleDays(messages)
        var lastDayLabel: String? = null

        if (!spansMultipleDays) {
            sb.append("${dayFormatter.format(Date(messages.first().timestamp))}\n")
        }

        messages.forEach { msg ->
            val dayLabel = dayFormatter.format(Date(msg.timestamp))

            if (spansMultipleDays && dayLabel != lastDayLabel) {
                sb.append("$dayLabel\n")
                sb.append("────────────────────────\n")
                lastDayLabel = dayLabel
            }

            val senderLabel = if (msg.isSent) "You" else threadDisplayName
            val time = timeFormatter.format(Date(msg.timestamp))
            sb.append("$senderLabel ($time)\n")
            val note = attachmentNote(msg)
            if (msg.body.isNotBlank() || note == null) sb.append("${msg.body}\n")
            note?.let { sb.append("$it\n") }

            if (msg.reactions.isNotEmpty()) {
                val grouped = msg.reactions
                    .groupBy { it.emoji }
                    .map { (emoji, reactions) ->
                        val names = reactions.joinToString(", ") { r ->
                            if (r.senderAddress == ownAddress) "You" else threadDisplayName
                        }
                        "$emoji $names"
                    }
                    .joinToString("  ")
                sb.append("  ↩ $grouped\n")
            }
            sb.append("\n")
        }

        return sb.toString().trimEnd()
    }

    private fun spansMultipleDays(messages: List<Message>): Boolean {
        if (messages.size < 2) return false
        val dayFormatter = dayFormatter()
        val firstDay = dayFormatter.format(Date(messages.first().timestamp))
        val lastDay = dayFormatter.format(Date(messages.last().timestamp))
        return firstDay != lastDay
    }
}
