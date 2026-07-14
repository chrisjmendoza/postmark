package com.plusorminustwo.postmark.domain.backup

import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Thread
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a backup/export run covers. The defaults describe the scheduled global
 * backup: every thread (minus NEVER_INCLUDE opt-outs), all of time. The Export
 * screen narrows either axis; the archive format itself is selection-agnostic,
 * so a partial export restores exactly like a full backup.
 *
 * @param threadIds explicit conversation picks; null = all threads.
 * @param startMs   inclusive lower bound on message timestamps.
 * @param endMs     inclusive upper bound on message timestamps.
 */
data class BackupSelection(
    val threadIds: Set<Long>? = null,
    val startMs: Long = 0L,
    val endMs: Long = Long.MAX_VALUE
) {
    val hasDateRange: Boolean get() = startMs > 0L || endMs < Long.MAX_VALUE
}

/**
 * Which threads a selection covers:
 * - Explicit picks are exact — a user who deliberately checks a conversation gets
 *   it even if its backupPolicy is NEVER_INCLUDE (a deliberate export beats a
 *   standing policy).
 * - Select-all honors NEVER_INCLUDE, keeping that setting's promise ("always
 *   excluded from backups") for whole-corpus runs.
 */
fun selectThreadsForExport(all: List<Thread>, selection: BackupSelection): List<Thread> =
    selection.threadIds?.let { picked -> all.filter { it.id in picked } }
        ?: all.filter { it.backupPolicy != BackupPolicy.NEVER_INCLUDE }

/**
 * Converts picked calendar days to inclusive epoch-millis bounds in [zone]:
 * start-of-day of [start] through the last millisecond of [end]. The Material3
 * DateRangePicker hands back UTC-midnight millis that the UI converts to
 * [LocalDate]s (existing convention in ThreadScreen's DateRangeBottomSheet);
 * this maps those calendar days onto the local timeline message timestamps use.
 */
fun localDateRangeToMillisBounds(
    start: LocalDate,
    end: LocalDate,
    zone: ZoneId = ZoneId.systemDefault()
): Pair<Long, Long> {
    val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMs = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    return startMs to endMs
}

/**
 * In-memory filter for the Export screen's search box — matches nickname,
 * display name, or address, case-insensitively. Blank query = everything.
 */
fun filterThreadsForExport(threads: List<Thread>, query: String): List<Thread> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return threads
    return threads.filter { thread ->
        thread.nickname?.contains(trimmed, ignoreCase = true) == true ||
            thread.displayName.contains(trimmed, ignoreCase = true) ||
            thread.address.contains(trimmed, ignoreCase = true)
    }
}
