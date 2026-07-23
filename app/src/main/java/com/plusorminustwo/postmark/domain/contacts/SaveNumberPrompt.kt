package com.plusorminustwo.postmark.domain.contacts

/**
 * Pure rule for the "Add to contacts?" banner shown at the top of a 1:1 thread whose
 * address doesn't resolve to a saved contact.
 *
 * Mirrors [com.plusorminustwo.postmark.domain.spam.shouldShowSpamBanner]'s shape: display-only,
 * recomputed live from the thread's current state, with only the user's dismissal persisted
 * (see `SaveNumberPromptRepository`). The spam banner always wins — a thread never shows both
 * at once, so [spamBannerVisible] is a guard here.
 */

/** Fewest digits (after stripping everything else) a number needs to be worth offering to
 *  save — short codes and other non-personal senders fall under this and get no banner. */
const val MIN_SAVEABLE_PHONE_DIGITS = 7

/**
 * True when [address] looks like an ordinary personal phone number worth adding to contacts:
 * at least [MIN_SAVEABLE_PHONE_DIGITS] digits once every non-digit character (spaces,
 * punctuation, a leading `+`) is stripped. Short codes (5-6 digits) and alphanumeric sender
 * IDs (e.g. "AMAZON", which strips to zero digits) both fall short and return false.
 */
fun isSaveablePhoneNumber(address: String): Boolean =
    address.count { it.isDigit() } >= MIN_SAVEABLE_PHONE_DIGITS

/**
 * Whether the "Add to contacts?" banner should be visible for a thread.
 *
 * True only when ALL hold: it's not a group thread, [contactName] is null/blank (the address
 * has no matching contact), [address] is a [isSaveablePhoneNumber], the user hasn't already
 * dismissed the banner for this thread, and the spam-suspicion banner isn't currently showing
 * (that one takes priority — never both at once).
 */
fun shouldShowSaveNumberPrompt(
    isGroup: Boolean,
    contactName: String?,
    address: String,
    dismissed: Boolean,
    spamBannerVisible: Boolean
): Boolean =
    !isGroup && contactName.isNullOrBlank() && isSaveablePhoneNumber(address) &&
        !dismissed && !spamBannerVisible
