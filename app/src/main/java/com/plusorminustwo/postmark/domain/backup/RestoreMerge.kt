package com.plusorminustwo.postmark.domain.backup

import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.RESTORED_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.Thread

/*
 * Pure decision logic for restore: message identity across devices/reinstalls,
 * duplicate detection, thread-metadata merging, and restored-row id allocation.
 * All of it is here (not in RestoreWorker) so the rules that decide what restore
 * does to a user's data are pinned by JVM tests.
 */

// Delivery-status values duplicated from MessageEntity's DELIVERY_STATUS_* constants
// (domain must not depend on the data layer).
private const val STATUS_NONE = 0
private const val STATUS_PENDING = 1
private const val STATUS_FAILED = 4

/**
 * Canonical form of a phone address for cross-device matching. Provider address
 * formatting varies between devices ("+12065551234" vs "206-555-1234"), so real
 * numbers compare by their last 10 digits; short codes keep all their digits;
 * alphanumeric senders (banks, email gateways) compare case-insensitively.
 */
fun normalizeAddress(raw: String): String {
    val trimmed = raw.trim()
    val digits = trimmed.filter { it.isDigit() }
    return when {
        digits.length >= 10 -> digits.takeLast(10)
        digits.isNotEmpty() && trimmed.none { it.isLetter() } -> digits
        else -> trimmed.lowercase()
    }
}

/**
 * Stable identity of a message independent of device-local row ids. Two rows with
 * the same fingerprint are the same message: same direction, transport, timestamp
 * (millis, copied verbatim from the original provider row on both sides), sender
 * and body.
 */
fun messageFingerprint(
    address: String,
    timestamp: Long,
    isSent: Boolean,
    isMms: Boolean,
    body: String
): String = buildString(body.length + 24) {
    append(if (isMms) 'M' else 'S')
    append(if (isSent) '>' else '<')
    append(timestamp)
    append('|')
    append(normalizeAddress(address))
    append('|')
    append(body)
}

/**
 * Multiset index of one local thread's messages, keyed by fingerprint.
 *
 * A multiset rather than a set so genuinely identical messages (same
 * address/millisecond/body — rare but representable) keep their multiplicity:
 * each backup duplicate consumes one local copy, and any excess is restored.
 */
class RestoreMessageIndex {
    private val byFingerprint = HashMap<String, ArrayDeque<Long>>()

    fun add(fingerprint: String, localMessageId: Long) {
        byFingerprint.getOrPut(fingerprint) { ArrayDeque() }.addLast(localMessageId)
    }

    /** If an unconsumed local message matches, consumes it and returns its id
     *  (the restore target for reaction merging); null means "not present locally"
     *  and the caller should insert. */
    fun consumeMatch(fingerprint: String): Long? =
        byFingerprint[fingerprint]?.removeFirstOrNull()
}

/**
 * Field updates to apply to an existing local thread, null = leave untouched.
 * A backup value is applied only where the local field still has its default —
 * restore never clobbers a choice the user has made on this device, and never
 * clears anything.
 */
data class ThreadMetadataUpdates(
    val nickname: String? = null,
    val isPinned: Boolean? = null,
    val isMuted: Boolean? = null,
    val notificationsEnabled: Boolean? = null,
    val backupPolicy: BackupPolicy? = null
) {
    val isEmpty: Boolean
        get() = nickname == null && isPinned == null && isMuted == null &&
            notificationsEnabled == null && backupPolicy == null
}

fun mergeThreadMetadata(local: Thread, record: ThreadRecord): ThreadMetadataUpdates {
    val recordPolicy = decodeBackupPolicy(record.backupPolicy)
    return ThreadMetadataUpdates(
        nickname = record.nickname.takeIf { local.nickname == null },
        isPinned = true.takeIf { record.isPinned && !local.isPinned },
        isMuted = true.takeIf { record.isMuted && !local.isMuted },
        notificationsEnabled =
            false.takeIf { !record.notificationsEnabled && local.notificationsEnabled },
        backupPolicy = recordPolicy.takeIf {
            it != BackupPolicy.GLOBAL && local.backupPolicy == BackupPolicy.GLOBAL
        }
    )
}

/** Maps a backed-up policy name to the enum, defaulting unknown values to GLOBAL
 *  (an old app reading a newer backup must not crash on a new policy name). */
fun decodeBackupPolicy(name: String): BackupPolicy =
    BackupPolicy.entries.firstOrNull { it.name == name } ?: BackupPolicy.GLOBAL

/**
 * Normalizes a message record before insertion:
 * - PENDING → NONE: nothing will ever resolve a restored in-flight status, it would
 *   render as a clock spinner forever.
 * - FAILED → NONE: a restored FAILED renders an active retry button that would
 *   re-send a years-old text; SENT/DELIVERED are preserved as historical fact.
 * - isRead forced true: honoring backed-up unread flags would flood the unread
 *   badges with historical messages.
 */
fun sanitizeForRestore(record: MessageRecord): MessageRecord = record.copy(
    deliveryStatus = when (record.deliveryStatus) {
        STATUS_PENDING, STATUS_FAILED -> STATUS_NONE
        else -> record.deliveryStatus
    },
    isRead = true
)

/** First id for newly restored rows given the highest already-used restored id
 *  (null when none exist). Seeding from the existing max keeps a resumed or repeated
 *  restore from colliding with rows a previous run inserted. */
fun firstRestoredId(maxExistingRestoredId: Long?): Long =
    maxOf(maxExistingRestoredId ?: 0L, RESTORED_ID_OFFSET - 1) + 1

/** True for files the backup system owns — both v1 (.json) and v2 (.zip) share one
 *  retention pool, so old v1 files age out alongside new archives. User-initiated
 *  exports (`postmark_export_*`) are deliberately NOT backup files: they may be
 *  saved into the same folder as scheduled backups, and retention pruning must
 *  never delete a slice the user exported to keep. */
fun isBackupFileName(name: String): Boolean =
    name.startsWith("postmark_") &&
        !name.startsWith("postmark_export_") &&
        (name.endsWith(".json") || name.endsWith(".zip"))

/**
 * Stable fallback thread id for the edge case where the platform refuses
 * `Telephony.Threads.getOrCreateThreadId`. Negative so it can never collide with a
 * real system thread id (always positive); derived from the normalized address so
 * re-running restore maps the same conversation to the same fallback thread.
 * (Negative *message* ids mark optimistic rows and get cleaned up by sync, but that
 * logic never touches thread ids.)
 */
fun fallbackThreadId(address: String): Long {
    val hash = normalizeAddress(address).hashCode().toLong()
    return -(hash.mod(1_000_000_000L) + 1L)
}
