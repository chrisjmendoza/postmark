package com.plusorminustwo.postmark.domain.spam

/**
 * Conservative, pure spam heuristics.
 *
 * This layer only ever *suspects* a message of being spam so the UI can offer a
 * dismissable "Looks like spam?" banner. It NEVER auto-moves a thread into the Spam
 * folder — that stays an explicit user action ([looksLikeSpam] has no side effects and
 * touches no storage).
 *
 * Design bias is toward false negatives (missing real spam) over false positives
 * (nagging on a real conversation): the banner is only offered when EVERY cheap signal
 * lines up.
 */

/** Longest a body (trimmed) can be and still read as a throwaway spam blast. */
const val SPAM_MAX_BODY_LENGTH = 200

/**
 * Matches the two URL shapes a spam text almost always carries:
 *  1. An explicit `http://` / `https://` / `www.` link, then any non-space run.
 *  2. A bare `domain.tld` (optionally `sub.domain.tld`, optionally a `/path`), where the
 *     TLD is 2+ letters — enough to catch `bit.ly/x` and `claim-now.example` without a
 *     scheme.
 *
 * Deliberately simple (see CLAUDE.md — keep the regex simple and documented). It can
 * misread a period-joined pair like `ok.thanks` as a bare domain, but that only ever
 * *suspects*, and only when the sender is also unknown and the body short — the banner is
 * dismissable, so a rare over-suspicion costs one tap, never a lost message.
 */
private val SPAM_URL_REGEX = Regex(
    """(https?://|www\.)\S+|\b[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,}(/\S*)?""",
    RegexOption.IGNORE_CASE
)

/**
 * True only when a message looks like an unsolicited spam blast:
 * the sender is NOT a saved contact, the thread is NOT a group, the (trimmed) body is
 * short (< [SPAM_MAX_BODY_LENGTH]) and non-empty, and it contains a URL.
 *
 * Any of: known contact, group thread, empty/long body, or no URL → false.
 */
fun looksLikeSpam(
    body: String,
    senderIsKnownContact: Boolean,
    isGroupThread: Boolean
): Boolean {
    if (senderIsKnownContact || isGroupThread) return false
    val trimmed = body.trim()
    if (trimmed.isEmpty() || trimmed.length >= SPAM_MAX_BODY_LENGTH) return false
    return SPAM_URL_REGEX.containsMatchIn(trimmed)
}

/**
 * Whether the suspected-spam banner should be visible for a thread.
 *
 * Extracted as a pure function so the (suspect && !already-spam && !dismissed) rule is
 * testable without a ViewModel. [suspect] is the recomputed [looksLikeSpam] result for
 * the thread's first received message; [isSpam] is the thread's stored flag; [dismissed]
 * is whether the user has already waved the banner away for this thread.
 */
fun shouldShowSpamBanner(
    suspect: Boolean,
    isSpam: Boolean,
    dismissed: Boolean
): Boolean = suspect && !isSpam && !dismissed
