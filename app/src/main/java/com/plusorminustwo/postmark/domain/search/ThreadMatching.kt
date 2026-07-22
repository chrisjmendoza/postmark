package com.plusorminustwo.postmark.domain.search

import com.plusorminustwo.postmark.domain.model.Thread

/** Cap on the "Conversations" section of search, matching the app's other short lists. */
const val CONTACT_MATCH_LIMIT = 5

/**
 * Filters [threads] to those whose contact name or address matches [query], for the
 * "Conversations" section of the search screen. Pure in-memory transform — no DB query,
 * no Android dependencies. Lets a user find a contact by name/number without scrolling
 * the full conversation list, even when no message body matches.
 *
 * Matching (all case-insensitive):
 *  - name: the visible name ([Thread.nickname] falling back to [Thread.displayName]) or
 *    the underlying [Thread.displayName] contains the query;
 *  - address: [Thread.address] contains the query verbatim, OR — when the query contains
 *    digits — the address's digits contain the query's digits, so "(555) 123" matches
 *    "+1 555-1234" regardless of punctuation. (A plain digit filter is used rather than
 *    [com.plusorminustwo.postmark.domain.model.normalizeAddressForDedupe], whose leading-1
 *    stripping is meant for whole-number dedupe, not substring matching.)
 *
 * Ordering: name matches rank above address-only matches, then alphabetically by the
 * visible name (case-insensitive), with thread id as a stable tiebreak. Result is capped
 * at [limit]. A blank query yields an empty list (no section shown).
 */
fun matchThreads(
    query: String,
    threads: List<Thread>,
    limit: Int = CONTACT_MATCH_LIMIT
): List<Thread> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val qDigits = q.filter { it.isDigit() }

    // 0 = name match (ranks first), 1 = address-only match, null = no match.
    fun rank(thread: Thread): Int? {
        val shown = thread.nickname ?: thread.displayName
        if (shown.lowercase().contains(q) || thread.displayName.lowercase().contains(q)) return 0
        val addrDigitMatch = qDigits.isNotEmpty() &&
            thread.address.filter { it.isDigit() }.contains(qDigits)
        return if (thread.address.lowercase().contains(q) || addrDigitMatch) 1 else null
    }

    return threads
        .mapNotNull { t -> rank(t)?.let { t to it } }
        .sortedWith(
            compareBy(
                { it.second },
                { (it.first.nickname ?: it.first.displayName).lowercase() },
                { it.first.id }
            )
        )
        .map { it.first }
        .take(limit)
}
