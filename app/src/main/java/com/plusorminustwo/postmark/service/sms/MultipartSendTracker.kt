package com.plusorminustwo.postmark.service.sms

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates the per-part "sent" results of a (possibly multipart) SMS so the whole
 * message reaches a single, correct final status instead of the last-part-wins race the
 * receiver used to have.
 *
 * Pure — no Android imports, so it is exercised directly in [MultipartSendTrackerTest].
 * A carrier splits an SMS over 160 chars into N PDUs, each with its own sent
 * [android.app.PendingIntent] carrying the same message identity (the Room `key`). The
 * platform fires those callbacks independently and out of order, and can even re-fire a
 * given part. This class collapses that stream into one [Decision] per message:
 *
 *  - [Decision.MarkFailed] exactly once, on the **first** [Outcome.KnownFailure] for a key.
 *    A failed part means the recipient got a truncated/broken message, so failure is
 *    terminal for the send phase: every subsequent part (Ok or fail) then yields
 *    [Decision.None]. This is the whole point — a later part's Ok can no longer overwrite
 *    a real failure back to SENT.
 *  - [Decision.MarkSent] only once ALL [partCount] parts have reported [Outcome.Ok].
 *  - [Outcome.Ambiguous] (resultCode 0 — a cancelled PendingIntent, not a real failure)
 *    never counts toward the all-Ok condition, so a message with an ambiguous part simply
 *    never reaches MarkSent and stays PENDING — matching today's single-part semantic,
 *    where the delivery receipt or a later sync catch-up rescues it.
 *
 * Duplicate deliveries of the same [partIndex] don't double-count (Ok parts are held in a
 * Set), and once a key has emitted its terminal decision the entry is retained only as a
 * lightweight "already decided" marker (further parts → [Decision.None]) — it can't restart
 * counting. Retaining rather than immediately deleting the marker is deliberate: the other
 * parts of the same send fire milliseconds later, and deleting on the first decision would
 * let a straggler Ok re-open a message we already marked FAILED. Stale markers are bounded
 * by an LRU cap ([MAX_TRACKED_KEYS]); there are no timers.
 *
 * ## Recovery-payload stashing
 * The receiver's sent-row recovery needs the destination address/body, which
 * [SmsManagerWrapper] puts only on the **last** part's intent — but the part that finally
 * completes the message (producing MarkSent) may be a different, earlier part that reported
 * out of order. So whichever part carries a [RecoveryPayload] stashes it on the entry as it
 * arrives, and [Decision.MarkSent] hands that stashed payload back regardless of which part
 * triggered the completion.
 *
 * ## Process-death caveat
 * State is in-memory only. If the process dies mid-send, the not-yet-reported parts produce
 * no callback into a fresh process, so the message stays PENDING forever (until a sync
 * catch-up or delivery receipt moves it). That is accepted, and strictly better than the
 * old behaviour of a single part's Ok prematurely marking the whole message SENT.
 */
@Singleton
class MultipartSendTracker @Inject constructor() {

    /** Per-part send result, mapped from the receiver's resultCode. */
    enum class Outcome { Ok, KnownFailure, Ambiguous }

    /** Address/body needed to re-create a missing `content://sms/sent` row (see receiver). */
    data class RecoveryPayload(val address: String, val body: String)

    /** The aggregate instruction the receiver acts on after recording one part. */
    sealed interface Decision {
        /** All parts reported Ok. [recovery] is the stashed payload, or null if none arrived. */
        data class MarkSent(val recovery: RecoveryPayload?) : Decision
        /** The first part failed; mark the whole message FAILED. */
        data object MarkFailed : Decision
        /** Nothing to do yet (still awaiting parts, ambiguous part, or already decided). */
        data object None : Decision
    }

    private enum class Terminal { SENT, FAILED }

    private class Entry(val partCount: Int) {
        val okParts = HashSet<Int>()
        var recovery: RecoveryPayload? = null
        var terminal: Terminal? = null
    }

    // accessOrder = true so get/put count as "use", making removeEldestEntry a true LRU.
    private val entries = object : LinkedHashMap<Long, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>): Boolean =
            size > MAX_TRACKED_KEYS
    }

    /**
     * Records one part's [outcome] for message [key] and returns the aggregate [Decision].
     *
     * @param partIndex 0-based index of this part (single-part sends use 0).
     * @param partCount total number of parts for this message (single-part sends use 1).
     * @param recovery the sent-row recovery payload if this part's intent carried it
     *   (last part only), else null — stashed on the entry until a MarkSent decision.
     */
    @Synchronized
    fun record(
        key: Long,
        partIndex: Int,
        partCount: Int,
        outcome: Outcome,
        recovery: RecoveryPayload? = null,
    ): Decision {
        val entry = entries.getOrPut(key) { Entry(partCount) }
        if (recovery != null) entry.recovery = recovery

        // Terminal is terminal: once SENT or FAILED emitted, every straggler part is a no-op.
        if (entry.terminal != null) return Decision.None

        return when (outcome) {
            Outcome.Ambiguous -> Decision.None // never contributes to the all-Ok condition
            Outcome.KnownFailure -> {
                entry.terminal = Terminal.FAILED
                Decision.MarkFailed
            }
            Outcome.Ok -> {
                entry.okParts.add(partIndex) // Set → duplicate re-fire can't double-count
                if (entry.okParts.size >= entry.partCount) {
                    entry.terminal = Terminal.SENT
                    Decision.MarkSent(entry.recovery)
                } else {
                    Decision.None
                }
            }
        }
    }

    /**
     * Forgets all state for [key] so the next [record] for it starts fresh.
     *
     * Needed by the offline send queue: a message that failed its first send ends with a
     * terminal FAILED marker for its key (the Room id), which is *retained* (see class doc)
     * so stragglers can't reopen it. But a queued re-send reuses that same key — without
     * clearing it, every part of the re-send hits the `terminal != null` guard and returns
     * [Decision.None], leaving the message stuck PENDING forever. [SendQueueWorker] calls
     * this before re-dispatching so the retry aggregates as a brand-new send.
     */
    @Synchronized
    fun reset(key: Long) {
        entries.remove(key)
    }

    /** Number of keys currently held (live + terminal markers). Test/diagnostic only. */
    @Synchronized
    fun trackedKeyCount(): Int = entries.size

    companion object {
        /** LRU cap on tracked keys — bounds memory without timers; see class doc. */
        const val MAX_TRACKED_KEYS = 512
    }
}
