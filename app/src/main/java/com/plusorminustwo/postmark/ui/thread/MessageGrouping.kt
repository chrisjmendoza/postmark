package com.plusorminustwo.postmark.ui.thread

import com.plusorminustwo.postmark.domain.model.Message

enum class ClusterPosition { SINGLE, TOP, MIDDLE, BOTTOM }

private val CLUSTER_GAP_MS = 3 * 60 * 1_000L  // 3 minutes

/**
 * Assigns a [ClusterPosition] to every message in [messages] (ascending timestamp order).
 *
 * A cluster is a run of consecutive messages from the same sender where each
 * adjacent pair is no more than [CLUSTER_GAP_MS] apart. Position is determined
 * purely by chronological adjacency — day boundaries are ignored.
 */
fun computeClusterPositions(messages: List<Message>): Map<Long, ClusterPosition> {
    if (messages.isEmpty()) return emptyMap()
    val result = HashMap<Long, ClusterPosition>(messages.size)
    for (i in messages.indices) {
        val cur  = messages[i]
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        val attachedToPrev = prev != null &&
            prev.isSent == cur.isSent &&
            cur.timestamp - prev.timestamp <= CLUSTER_GAP_MS
        val attachedToNext = next != null &&
            next.isSent == cur.isSent &&
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
