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

    private val dayFormatter = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

    /**
     * Renders [messages] as a plain-text transcript suitable for sharing.
     *
     * @param messages The messages to include, in chronological order.
     * @param threadDisplayName Display name of the other party (contact or phone number).
     * @param ownAddress The user's own phone number (used to label sent messages as "You").
     * @return A formatted string, or an empty string if [messages] is empty.
     */
    fun formatForCopy(
        messages: List<Message>,
        threadDisplayName: String,
        ownAddress: String
    ): String {
        if (messages.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("Conversation with $threadDisplayName\n")

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
            sb.append("${msg.body}\n")

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
        val firstDay = dayFormatter.format(Date(messages.first().timestamp))
        val lastDay = dayFormatter.format(Date(messages.last().timestamp))
        return firstDay != lastDay
    }
}
