package com.plusorminustwo.postmark.ui.thread

import android.os.Trace
import androidx.compose.runtime.Immutable
import com.plusorminustwo.postmark.domain.model.Message

// ── ThreadListItem ─────────────────────────────────────────────────────────────
/**
 * A single item in the flat list that [LazyColumn] renders in the thread screen.
 *
 * Keeping the list flat (rather than nested date-group loops) means:
 *  - LazyColumn can track stable keys for every item without re-executing a forEach.
 *  - Item identity is never lost on recomposition — Compose reuses slots correctly.
 *  - Scroll-to-index is a simple list lookup instead of re-grouping messages.
 *
 * Both subtypes are @Immutable so Compose can skip recomposing bubbles/headers
 * whose data hasn't changed.
 */
@Immutable
sealed interface ThreadListItem {
    /** Stable key used by LazyColumn to track composition slots across recompositions. */
    val key: String

    /**
     * A message bubble row.
     *
     * @param key             String form of [message].id — stable across recompositions.
     * @param message         Full message data (body, delivery status, reactions, etc.).
     * @param clusterPosition Visual bubble shape — TOP / MIDDLE / BOTTOM / SINGLE within a run.
     */
    @Immutable
    data class Bubble(
        override val key: String,
        val message: Message,
        val clusterPosition: ClusterPosition
    ) : ThreadListItem

    /**
     * A date-separator header row (e.g. "May 8, 2026").
     *
     * @param key        "header_$dateLabel" — stable across recompositions.
     * @param dateLabel  Human-readable date string shown in the separator.
     * @param messageIds All message IDs on this day; used for the day-select toggle.
     */
    @Immutable
    data class DateHeader(
        override val key: String,
        val dateLabel: String,
        val messageIds: List<Long>
    ) : ThreadListItem
}

// ── ThreadRenderState ──────────────────────────────────────────────────────────
/**
 * Pre-computed display model derived from the raw message list.
 *
 * Computed once in the ViewModel whenever [messages] changes, so the composable
 * never re-derives these structures on the main thread.
 *
 * @param items              Flat ordered list for LazyColumn (newest message at index 0,
 *                           oldest at the end — matches reverseLayout = true).
 * @param dateToHeaderIndex  Maps a day label to its LazyColumn item index; used to
 *                           scroll to a date from the calendar picker.
 * @param messageIdToIndex   Maps a message ID to its LazyColumn item index; used to
 *                           scroll to a specific message from search results.
 * @param messageIdToDate    Maps a message ID to its day label; used by the floating
 *                           date pill to show which day is currently visible.
 */
data class ThreadRenderState(
    val items: List<ThreadListItem> = emptyList(),
    val dateToHeaderIndex: Map<String, Int> = emptyMap(),
    val messageIdToIndex: Map<Long, Int> = emptyMap(),
    val messageIdToDate: Map<Long, String> = emptyMap()
)

// ── Builder ───────────────────────────────────────────────────────────────────
/**
 * Converts a flat [messages] list (ascending timestamp) into a [ThreadRenderState].
 *
 * This is a pure function extracted here so it can be called from the ViewModel
 * (off the main thread) and tested without constructing a ViewModel.
 *
 * Layout order matches LazyColumn with reverseLayout = true:
 *   index 0 = newest message, higher indices = older content.
 * Day groups: newest day first, headers placed after their day's messages so the
 * header appears visually above the day's messages when layout is reversed.
 */
fun buildRenderState(messages: List<Message>): ThreadRenderState {
    if (messages.isEmpty()) return ThreadRenderState()

    // Trace markers show up in Android Studio CPU Profiler / Perfetto systrace,
    // letting us measure how long grouping + clustering takes per message-set change.
    Trace.beginSection("ThreadRenderState.build")
    try {
        val clusterPositions = computeClusterPositions(messages)
        val grouped          = messages.groupByDay()

        val items            = ArrayList<ThreadListItem>(messages.size + grouped.size)
        val dateToHeaderIndex = HashMap<String, Int>(grouped.size)
        val messageIdToIndex  = HashMap<Long, Int>(messages.size)
        val messageIdToDate   = HashMap<Long, String>(messages.size)

        var idx = 0
        grouped.entries.reversed().forEach { (dateLabel, dayMessages) ->
            // Messages within a day are reversed so the newest appears at the smallest index.
            val reversed = dayMessages.reversed()
            reversed.forEach { msg ->
                messageIdToIndex[msg.id] = idx
                messageIdToDate[msg.id]  = dateLabel
                items += ThreadListItem.Bubble(
                    key             = msg.id.toString(),
                    message         = msg,
                    clusterPosition = clusterPositions[msg.id] ?: ClusterPosition.SINGLE
                )
                idx++
            }
            // Header comes after the day's messages so it renders above them in reverseLayout.
            dateToHeaderIndex[dateLabel] = idx
            items += ThreadListItem.DateHeader(
                key        = "header_$dateLabel",
                dateLabel  = dateLabel,
                messageIds = dayMessages.map { it.id }
            )
            idx++
        }

        return ThreadRenderState(
            items             = items,
            dateToHeaderIndex = dateToHeaderIndex,
            messageIdToIndex  = messageIdToIndex,
            messageIdToDate   = messageIdToDate
        )
    } finally {
        Trace.endSection()
    }
}
