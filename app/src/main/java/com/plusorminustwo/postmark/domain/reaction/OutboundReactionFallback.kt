package com.plusorminustwo.postmark.domain.reaction

/**
 * Pure composition + gating logic for OUTBOUND reaction fallbacks — the
 * Android-Messages-style SMS (`👍 to "quoted text"`) Postmark sends so the recipient's
 * app shows a reaction instead of nothing.
 *
 * Kept free of Android and repository types so it is exercised directly in
 * OutboundReactionFallbackTest. The two halves are deliberately split:
 *
 *  - [composeReactionFallback] builds the wire string from an emoji + the target body,
 *    obeying a quote budget that keeps the message short and single-line.
 *  - [reactionFallbackRoundTrips] is the belt-and-braces gate: it re-parses the composed
 *    string with OUR OWN parser and re-matches the quote against the thread, and only
 *    approves the send when it resolves back to EXACTLY the message the user reacted to.
 *    Anything that doesn't round-trip (an emoji too long for the parser's `\S{1,8}`, an
 *    ambiguous short first line resolving to a different message, …) is left local-only.
 */

/**
 * Max characters of the target body quoted inside a fallback. Chosen small on purpose:
 * a reaction only needs enough of the original for the recipient's app to match it, and
 * a short quote keeps the whole message to a single SMS part. A longer body is truncated
 * to this budget with a trailing ellipsis (the same shape both Google and Apple emit),
 * which OUR matcher resolves via its truncated-quote strategy.
 */
const val OUTBOUND_QUOTE_BUDGET = 30

/**
 * Minimum characters that must survive budget-truncation before an ellipsized quote is
 * allowed. Mirrors [com.plusorminustwo.postmark.data.reaction.ReactionFallbackParser]'s
 * TRUNCATED_QUOTE_MIN_STEM (10): below it, the receiving matcher refuses to prefix-match,
 * so composing such a quote could never round-trip — return null instead.
 */
const val OUTBOUND_TRUNCATED_QUOTE_MIN_STEM = 10

/**
 * Builds the Android-format fallback body for reacting [emoji] to [targetBody], or null
 * when no faithful quote fits.
 *
 * Format: `<emoji> to "<quote>"`, and for a toggle-off `<emoji> to "<quote>" removed`
 * (straight double quotes — exactly what [com.plusorminustwo.postmark.data.reaction.AndroidReactionParser]
 * accepts).
 *
 * Quote rules:
 *  - Single-line-ify: if the body breaks within the budget window, the quote is just its
 *    first line; a later newline is never reached because budget-truncation stops first.
 *  - If the (single-line) source fits the budget, quote it whole.
 *  - Otherwise truncate to the budget, trim trailing whitespace, and append "…". The stem
 *    must stay ≥ [OUTBOUND_TRUNCATED_QUOTE_MIN_STEM] or this returns null.
 *  - A blank quote can never round-trip (the parser needs a non-empty capture) → null.
 */
fun composeReactionFallback(emoji: String, targetBody: String, isRemoval: Boolean): String? {
    val budget = OUTBOUND_QUOTE_BUDGET

    // Single-line-ify: cut at the first newline only when it falls inside the budget.
    // A newline at or beyond the budget is irrelevant — the truncation below stops before it.
    val newlineIdx = targetBody.indexOf('\n')
    val source = if (newlineIdx in 0 until budget) targetBody.take(newlineIdx) else targetBody

    val quote: String = if (source.length <= budget) {
        source
    } else {
        val stem = source.take(budget).trimEnd()
        if (stem.length < OUTBOUND_TRUNCATED_QUOTE_MIN_STEM) return null
        "$stem…"
    }

    if (quote.isBlank()) return null

    val suffix = if (isRemoval) " removed" else ""
    return "$emoji to \"$quote\"$suffix"
}

/**
 * True when [composed] can be sent safely: it parses back as a reaction fallback AND the
 * quote re-matches to exactly [targetMessageId] within the thread. Pure — the parser and
 * matcher are injected as lambdas so the real
 * [com.plusorminustwo.postmark.data.reaction.ReactionFallbackParser] can be wired in both
 * production and tests without any cross-layer type dependency here.
 *
 * @param quoteOf         Parses a body to its quoted text, or null if it isn't a fallback.
 * @param findOriginalId  Resolves a quote to the matched message's id (candidate pool must
 *                        already exclude the target's own fallbacks), or null if unresolved.
 */
inline fun reactionFallbackRoundTrips(
    composed: String?,
    targetMessageId: Long,
    quoteOf: (String) -> String?,
    findOriginalId: (quote: String) -> Long?,
): Boolean {
    if (composed == null) return false
    val quote = quoteOf(composed) ?: return false
    return findOriginalId(quote) == targetMessageId
}

/**
 * True when a queue-worthy SMS send failure whose body is a reaction fallback has no Room
 * row to carry the QUEUED status — because incremental sync already resolved the fallback
 * out of Room — so a fresh QUEUED row must be parked for SendQueueWorker to retry.
 *
 * Pure so the recovery gate is unit-tested without a receiver/DB. See
 * [com.plusorminustwo.postmark.service.sms.SmsSentDeliveryReceiver].
 */
fun shouldRequeueOrphanedReactionFallback(
    roomRowExists: Boolean,
    isReactionFallback: Boolean,
    address: String?,
    body: String?,
): Boolean = !roomRowExists &&
    isReactionFallback &&
    !address.isNullOrEmpty() &&
    !body.isNullOrEmpty()
