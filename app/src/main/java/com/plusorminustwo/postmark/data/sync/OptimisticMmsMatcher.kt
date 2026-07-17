package com.plusorminustwo.postmark.data.sync

import kotlin.math.abs

/** How far apart a real provider row's date and the optimistic row's creation time
 *  may be and still refer to the same send. persistSentMms writes the optimistic
 *  time itself (Δ < 1 s after second-truncation); the window is generous for
 *  provider clock oddities while still excluding hours-later RCS-archival rows. */
internal const val OPTIMISTIC_MMS_MATCH_WINDOW_MS = 15 * 60 * 1_000L

internal data class OptimisticCandidate(val id: Long, val body: String, val timestampMs: Long)

/** Picks which optimistic sent-MMS row (if any) a just-imported real sent row
 *  corresponds to: trimmed bodies must be equal AND timestamps within
 *  [OPTIMISTIC_MMS_MATCH_WINDOW_MS]; among qualifiers, smallest |Δt| wins, ties
 *  broken by larger id (the newer temp row). Returns the winning candidate id or
 *  null. Pure — JVM-tested. */
internal fun pickOptimisticMatch(
    realBody: String,
    realTimestampMs: Long,
    candidates: List<OptimisticCandidate>
): Long? {
    val trimmedReal = realBody.trim()
    return candidates
        .filter {
            it.body.trim() == trimmedReal &&
                abs(it.timestampMs - realTimestampMs) <= OPTIMISTIC_MMS_MATCH_WINDOW_MS
        }
        .minWithOrNull(
            compareBy<OptimisticCandidate> { abs(it.timestampMs - realTimestampMs) }
                .thenByDescending { it.id }
        )
        ?.id
}
