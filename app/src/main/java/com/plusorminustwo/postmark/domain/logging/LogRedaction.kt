package com.plusorminustwo.postmark.domain.logging

// ── LogRedaction ──────────────────────────────────────────────────────────────

/**
 * Redacts a message address for log lines so the sync log (shareable via
 * Dev Options) and its Logcat mirror never carry a full phone number or email.
 *
 * Keeps just enough to correlate log lines with a conversation while debugging:
 *  - Phone numbers (≥ 7 digits — the shortest real subscriber number) → "…1234"
 *  - Email addresses (MMS senders can be emails) → "…@domain"
 *  - Short codes (5–6 digits, e.g. "88202") pass through — they identify a
 *    service, not a person, and masking them would hide carrier-specific bugs.
 */
fun String.redactPhone(): String {
    if (contains('@')) return "…@" + substringAfter('@')
    val digits = filter { it.isDigit() }
    return if (digits.length >= 7) "…" + digits.takeLast(4) else this
}
