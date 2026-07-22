package com.plusorminustwo.postmark.domain.search

import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread

/**
 * One thread's worth of search results, for the BY_CONTACT grouped view.
 *
 * @param threadId    The thread these messages belong to — the grouping key. Two threads
 *                    with the same [displayName] stay separate groups because of this.
 * @param thread      The matching [Thread] from the roster, or null if none was found
 *                    (drives the section header's avatar accent color / address).
 * @param displayName Section-header label; the thread's display name, falling back to the
 *                    first message's raw address when no thread matched.
 * @param messages    This thread's matching messages, newest-first.
 */
data class SearchResultGroup(
    val threadId: Long,
    val thread: Thread?,
    val displayName: String,
    val messages: List<Message>
)

/**
 * Groups flat search [results] by thread for the BY_CONTACT sort order. Pure in-memory
 * transform — no Android or DB dependencies, no new DAO query.
 *
 * Groups are keyed by `threadId` (so two threads that happen to share a display name do
 * not merge), ordered case-insensitively A–Z by display name, and the messages within
 * each group are sorted newest-first.
 */
fun groupResultsByContact(
    results: List<Message>,
    threads: List<Thread>
): List<SearchResultGroup> {
    val threadsById = threads.associateBy { it.id }
    return results
        .groupBy { it.threadId }
        .map { (threadId, msgs) ->
            val thread = threadsById[threadId]
            SearchResultGroup(
                threadId = threadId,
                thread = thread,
                displayName = thread?.displayName ?: msgs.first().address,
                messages = msgs.sortedByDescending { it.timestamp }
            )
        }
        .sortedWith(
            compareBy({ it.displayName.lowercase() }, { it.threadId })
        )
}
