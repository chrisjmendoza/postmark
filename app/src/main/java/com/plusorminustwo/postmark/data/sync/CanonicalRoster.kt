package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.net.Uri

/**
 * Canonical-roster helpers (GROUP_MESSAGING_SPEC §1.3 / §1.4).
 *
 * The OS keeps its own authoritative conversation roster in the telephony
 * mms-sms tables. When AOSP's PduPersister creates a telephony thread it excludes
 * the device's own line number, so `recipient_ids` for a 1:1 conversation holds
 * exactly one id — unlike a per-PDU addr scan, which also carries the local TO row
 * and thus mislabels 1:1 MMS threads as groups (see [parseMmsParticipants] KDoc).
 *
 * Two URIs, both stable since API 1 and queryable by the default SMS app:
 *   content://mms-sms/conversations?simple=true  → _id, recipient_ids (space-separated)
 *   content://mms-sms/canonical-addresses        → _id, address
 */

/**
 * Pure function: parses a `recipient_ids` value (a space-separated list of canonical
 * address ids) into an ordered list of [Long] ids. Tolerant of leading/trailing and
 * doubled whitespace and of non-numeric tokens (silently dropped). Order preserved.
 */
internal fun parseRecipientIdList(raw: String): List<Long> =
    raw.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }

/** Outcome of the one-shot roster repair for a single already-persisted thread. */
internal enum class RepairAction { KEEP, DEMOTE, REPLACE }

/**
 * Pure function: decides what a repair pass should do with a thread whose stored
 * roster is [current], given the freshly re-derived canonical roster [canonical].
 *
 * - canonical size ≤ 1 → DEMOTE: the thread is really 1:1 (the stored roster included
 *   the device's own number); clear participants and reset the 1:1 display name.
 * - canonical == current → KEEP: a real group whose roster is already correct.
 * - otherwise → REPLACE: a real group; swap in the canonical roster (also drops the
 *   user's own name from the title when the old roster carried it).
 */
internal fun repairAction(canonical: List<String>, current: List<String>): RepairAction = when {
    canonical.size <= 1  -> RepairAction.DEMOTE
    canonical == current -> RepairAction.KEEP
    else                 -> RepairAction.REPLACE
}

/**
 * Resolves the canonical participant roster for [threadId] (Room thread ids ARE
 * telephony thread ids here). Returns the roster in `recipient_ids` order, or null
 * when the conversations query yields no row / a blank `recipient_ids` / no resolvable
 * address — the signal for callers to fall back to the per-PDU addr path (sync) or to
 * skip the thread (repair) rather than risk corrupting it on an OEM quirk.
 *
 * A resolved roster of size 1 is a legitimate 1:1 answer (self already excluded by the
 * OS), distinct from the null "couldn't determine" case.
 */
internal fun Context.queryCanonicalParticipants(threadId: Long): List<String>? {
    val recipientIds = contentResolver.query(
        Uri.parse("content://mms-sms/conversations?simple=true"),
        arrayOf("_id", "recipient_ids"),
        "_id = ?", arrayOf(threadId.toString()), null
    )?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow("recipient_ids")) else null
    } ?: return null

    val ids = parseRecipientIdList(recipientIds)
    if (ids.isEmpty()) return null

    // Resolve each id to an address, then re-order to match recipient_ids.
    val byId = LinkedHashMap<Long, String>()
    contentResolver.query(
        Uri.parse("content://mms-sms/canonical-addresses"),
        arrayOf("_id", "address"),
        "_id IN (${ids.joinToString(",")})", null, null
    )?.use {
        val idIdx   = it.getColumnIndexOrThrow("_id")
        val addrIdx = it.getColumnIndexOrThrow("address")
        while (it.moveToNext()) {
            val addr = it.getString(addrIdx)
            if (!addr.isNullOrBlank()) byId[it.getLong(idIdx)] = addr
        }
    } ?: return null

    return ids.mapNotNull { byId[it] }.ifEmpty { null }
}
