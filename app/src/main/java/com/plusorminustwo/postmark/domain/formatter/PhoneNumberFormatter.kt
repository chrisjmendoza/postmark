package com.plusorminustwo.postmark.domain.formatter

import java.util.Locale

/**
 * Formats a phone number string for display.
 *
 * For US/Canada NANP numbers in E.164 format (e.g. +12065551234), returns
 * a locale-formatted string (e.g. "(206) 555-1234").
 *
 * International numbers that aren't NANP and short codes are returned unchanged.
 *
 * @param raw   The raw phone number string (e.g. "+12065551234").
 * @param locale The locale to use for formatting; defaults to [Locale.getDefault].
 * @return A human-readable phone number string, or [raw] if no formatting applies.
 */
fun formatPhoneNumber(raw: String, locale: Locale = Locale.getDefault()): String {
    if (raw.isBlank()) return raw

    // Short codes (≤6 digits) pass through unchanged.
    val digitsOnly = raw.filter { it.isDigit() }
    if (digitsOnly.length <= 6) return raw

    // NANP E.164: +1XXXXXXXXXX (11 digits starting with +1)
    val nanpE164 = Regex("""^\+1(\d{3})(\d{3})(\d{4})$""")
    val nanpMatch = nanpE164.matchEntire(raw.trim())
    if (nanpMatch != null) {
        val (area, exchange, subscriber) = nanpMatch.destructured
        return "($area) $exchange-$subscriber"
    }

    // Plain 10-digit NANP number (no country code)
    val nanpPlain = Regex("""^(\d{3})(\d{3})(\d{4})$""")
    val plainMatch = nanpPlain.matchEntire(digitsOnly)
    if (plainMatch != null) {
        val (area, exchange, subscriber) = plainMatch.destructured
        return "($area) $exchange-$subscriber"
    }

    // All other formats (international, unrecognized) pass through unchanged.
    return raw
}
