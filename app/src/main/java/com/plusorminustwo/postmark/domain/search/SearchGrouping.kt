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
 * @param messages    This thread's matching messages, ordered per the requested direction
 *                    (newest-first by default, oldest-first when requested).
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
 * not merge) and ordered case-insensitively A–Z by display name. The [oldestFirst] flag
 * controls only the within-group message order — group A–Z order is unaffected by it.
 */
fun groupResultsByContact(
    results: List<Message>,
    threads: List<Thread>,
    oldestFirst: Boolean = false
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
                messages = if (oldestFirst) msgs.sortedBy { it.timestamp }
                           else msgs.sortedByDescending { it.timestamp }
            )
        }
        .sortedWith(
            compareBy({ it.displayName.lowercase() }, { it.threadId })
        )
}

/**
 * Pure reducers over the BY_CONTACT per-group collapse state — a `Set<Long>` of collapsed
 * `threadId`s, held session-only in [com.plusorminustwo.postmark.ui.search.SearchViewModel]
 * (mirrors the existing `sortOrder`/`oldestFirst` pattern, never persisted).
 *
 * IMPORTANT: whenever a new results set arrives (query/filter change), the ViewModel resets
 * this set to empty (all-expanded) — a collapsed set computed against a *previous* query's
 * groups must never silently hide rows in a *new* query's results.
 */

/** Toggles [key] (a threadId) in [collapsed]. Pure — returns a new set. */
fun toggleGroupCollapsed(collapsed: Set<Long>, key: Long): Set<Long> =
    if (key in collapsed) collapsed - key else collapsed + key

/** Collapses every group in [groupKeys]. An empty input collapses nothing. */
fun collapseAll(groupKeys: Collection<Long>): Set<Long> = groupKeys.toSet()

/** Expands every group — always the empty collapsed set. */
fun expandAll(): Set<Long> = emptySet()

/**
 * True when at least one of [groupKeys] is currently expanded (not in [collapsed]). Drives
 * the collapse-all control: true → show "Collapse all", false → show "Expand all". An empty
 * [groupKeys] is vacuously false — nothing to collapse.
 */
fun anyGroupExpanded(collapsed: Set<Long>, groupKeys: Collection<Long>): Boolean =
    groupKeys.any { it !in collapsed }
