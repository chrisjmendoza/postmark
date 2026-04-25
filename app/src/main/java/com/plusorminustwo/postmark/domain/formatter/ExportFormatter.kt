package com.plusorminustwo.postmark.domain.formatter

import com.plusorminustwo.postmark.domain.model.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportFormatter {

    private val dayFormatter = SimpleDateFormat("MMMM d", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

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

            msg.reactions.forEach { reaction ->
                val reactorName = if (reaction.senderAddress == ownAddress) "You" else threadDisplayName
                sb.append("${reaction.emoji} reacted by $reactorName\n")
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
