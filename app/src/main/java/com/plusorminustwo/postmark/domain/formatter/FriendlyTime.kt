package com.plusorminustwo.postmark.domain.formatter

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Recency-appropriate label for a message timestamp, matching iMessage / Google
 * Messages conventions on the conversation list:
 *
 *  - < 1 minute ago         → "just now"
 *  - < 60 minutes ago       → "Xm"         (e.g. "5m")
 *  - same calendar day      → wall-clock    ("9:41 AM", or "09:41" when [is24Hour])
 *  - within the last 6 days → short weekday  ("Mon")
 *  - same calendar year     → "Apr 25"
 *  - older                  → "4/25/23"     (includes the year)
 *
 * Pure and deterministic: [nowMs], [zone] and [locale] are all injected, so the
 * result depends only on its arguments and never on the wall clock — which is what
 * makes every band boundary unit-testable. Callers pass System.currentTimeMillis()
 * for [nowMs] and DateFormat.is24HourFormat(context) for [is24Hour].
 *
 * @param timestampMs The message time, epoch millis.
 * @param nowMs       "Now", epoch millis — injected rather than read here.
 * @param is24Hour    Whether to render the same-day time in 24-hour form.
 * @param zone        Zone used to resolve calendar day/year; defaults to the device zone.
 * @param locale      Locale for weekday/month names; defaults to the device locale.
 */
fun friendlyTimestamp(
    timestampMs: Long,
    nowMs: Long,
    is24Hour: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val diff = nowMs - timestampMs

    // ── Very recent ────────────────────────────────────────────────────────────
    if (diff < 60_000L) return "just now"
    if (diff < 60 * 60_000L) return "${diff / 60_000}m"

    // ── Calendar comparisons ───────────────────────────────────────────────────
    val msgTime = Instant.ofEpochMilli(timestampMs).atZone(zone)
    val msgDate = msgTime.toLocalDate()
    val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val daysAgo = diff / (24 * 60 * 60_000L)

    val timePattern = if (is24Hour) "HH:mm" else "h:mm a"
    return when {
        // Today — wall-clock time.
        msgDate == nowDate           -> msgTime.format(DateTimeFormatter.ofPattern(timePattern, locale))
        // Within the last 6 days — short weekday (Mon, Tue …).
        daysAgo < 7                  -> msgTime.format(DateTimeFormatter.ofPattern("EEE", locale))
        // Same year — "Apr 25".
        msgDate.year == nowDate.year -> msgTime.format(DateTimeFormatter.ofPattern("MMM d", locale))
        // Older — "4/25/23" (year included so it can't be mistaken for a recent date).
        else                         -> msgTime.format(DateTimeFormatter.ofPattern("M/d/yy", locale))
    }
}
