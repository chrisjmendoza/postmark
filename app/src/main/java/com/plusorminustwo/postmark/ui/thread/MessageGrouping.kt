package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.domain.model.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Describes where a message bubble sits within a run of consecutive same-sender messages. */
enum class ClusterPosition { SINGLE, TOP, MIDDLE, BOTTOM }

internal val DAY_FORMATTER = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).also {
    it.timeZone = java.util.TimeZone.getDefault()
}

/** "Sat, Jul 5, 2026 5:34 PM" — full date + time, used by the full-screen image viewer's
 *  header. An earlier version showed only weekday + time ("Sat 5:34 PM"), which was
 *  ambiguous for anything more than a few days old — the full date removes that. */
internal val FRIENDLY_TIMESTAMP_FORMATTER = SimpleDateFormat("EEE, MMM d, yyyy h:mm a", Locale.getDefault()).also {
    it.timeZone = java.util.TimeZone.getDefault()
}

/**
 * Groups [this] list (expected in ascending timestamp order) into an ordered map of
 * day-label → messages. The map preserves insertion order, so keys are in ascending
 * chronological order — oldest day first. Callers that need newest-day-first should
 * iterate [Map.entries] reversed.
 */
fun List<Message>.groupByDay(): Map<String, List<Message>> =
    groupBy { DAY_FORMATTER.format(Date(it.timestamp)) }

private val CLUSTER_GAP_MS = 3 * 60 * 1_000L  // 3 minutes

/**
 * Assigns a [ClusterPosition] to every message in [messages] (ascending timestamp order).
 *
 * A cluster is a run of consecutive messages from the same sender where each
 * adjacent pair is no more than [CLUSTER_GAP_MS] apart. Position is determined
 * purely by chronological adjacency — day boundaries are ignored.
 */
/**
 * Maps each day label to the flat item index of its [DateHeader] in the reversed LazyColumn.
 *
 * Layout order (index 0 = visual bottom of screen):
 *   [newest-day messages…] [newest-day header] [next-day messages…] [next-day header] …
 *
 * For each day (newest-first), messages occupy the lower indices and the header follows
 * immediately after, so `headerIndex = runningMessageCount`.
 */
fun buildDateToHeaderIndex(grouped: Map<String, List<Message>>): Map<String, Int> {
    var idx = 0
    return buildMap {
        grouped.entries.reversed().forEach { (label, messages) ->
            idx += messages.size
            put(label, idx)
            idx++
        }
    }
}

fun computeClusterPositions(messages: List<Message>): Map<Long, ClusterPosition> {
    if (messages.isEmpty()) return emptyMap()
    val result = HashMap<Long, ClusterPosition>(messages.size)
    for (i in messages.indices) {
        val cur  = messages[i]
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        val attachedToPrev = prev != null &&
            sameVisualSender(prev, cur) &&
            cur.timestamp - prev.timestamp <= CLUSTER_GAP_MS
        val attachedToNext = next != null &&
            sameVisualSender(cur, next) &&
            next.timestamp - cur.timestamp <= CLUSTER_GAP_MS
        result[cur.id] = when {
            attachedToPrev && attachedToNext -> ClusterPosition.MIDDLE
            attachedToPrev                   -> ClusterPosition.BOTTOM
            attachedToNext                   -> ClusterPosition.TOP
            else                             -> ClusterPosition.SINGLE
        }
    }
    return result
}

/**
 * Sent messages all render on the right regardless of their stored address, but
 * received messages in a group thread carry the actual sender in [Message.address] —
 * two participants texting back-to-back must not fuse into one bubble run.
 */
private fun sameVisualSender(a: Message, b: Message): Boolean =
    a.isSent == b.isSent && (a.isSent || a.address == b.address)
