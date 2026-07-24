package com.plusorminustwo.postmark.domain.links

import android.util.Patterns
import com.plusorminustwo.postmark.domain.model.Message
import java.util.regex.Pattern

/**
 * A URL match found by [findUrls]: the normalized (scheme-guaranteed) [url] plus the
 * `[start, end)` character range of the ORIGINAL match in the source text.
 *
 * @param url   Normalized URL — schemeless matches (`www.foo.com`, `foo.com`) get an
 *              `http://` prefix so `Intent.ACTION_VIEW` always has a scheme to resolve.
 * @param start Start offset of the raw match in the source text (for span callers).
 * @param end   End offset (exclusive) of the raw match in the source text.
 */
data class UrlMatch(val url: String, val start: Int, val end: Int)

/**
 * Finds every URL in [text] using [pattern] and normalizes each match to include a scheme.
 *
 * Defaults to `android.util.Patterns.WEB_URL` — the same field ThreadScreen's bubble-linkify
 * pass (`linkifyText`) matches against — so this is the ONE place that regex/matcher loop
 * lives; `linkifyText` calls into this function rather than keeping its own copy in sync by
 * hand, and a URL that's tappable in a bubble is therefore guaranteed to also be found here.
 *
 * @param pattern Overridable only for testing: `android.util.Patterns`' fields are stubbed to
 *                null outside Robolectric/an instrumented run, so plain-JUnit tests inject an
 *                equivalent plain [Pattern] fixture instead. Every production call site uses
 *                the default.
 */
fun findUrls(text: String, pattern: Pattern = Patterns.WEB_URL): List<UrlMatch> {
    val matcher = pattern.matcher(text)
    val matches = mutableListOf<UrlMatch>()
    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        val raw = matcher.group()
        // WEB_URL matches schemeless hosts (e.g. "example.com"); ACTION_VIEW needs a scheme.
        val url = if (raw.startsWith("http", ignoreCase = true)) raw else "http://$raw"
        matches += UrlMatch(url, start, end)
    }
    return matches
}

/**
 * One URL found inside a thread's message bodies, ready for display in the contact
 * profile's Links section.
 *
 * @param url        Normalized URL — see [UrlMatch.url].
 * @param messageId   The message this URL was found in — lets the row show a sender label.
 * @param timestamp   The message's timestamp — drives both ordering and the friendly-date label.
 * @param isSent      True when the containing message was sent by this device.
 */
data class LinkEntry(
    val url: String,
    val messageId: Long,
    val timestamp: Long,
    val isSent: Boolean
)

/**
 * Scans every message body in [messages] for URLs (via [findUrls]) and returns them newest
 * first, ready for the contact profile's Links section.
 *
 * A single body with multiple URLs contributes one entry per URL, in the order they appear
 * in the text. The same URL sent in two different messages produces two separate entries —
 * this is a timeline of what was shared, not a deduped list of destinations.
 *
 * [messages] may be supplied in any order; the result is always sorted by timestamp
 * descending (newest first). Ties (equal timestamps) keep their relative order from
 * [messages], and multi-URL bodies keep their in-text order within that tie — Kotlin's
 * `sortedByDescending` is a stable sort.
 *
 * @param urlPattern Forwarded to [findUrls]; see its doc — production callers never pass this.
 */
fun extractLinks(
    messages: List<Message>,
    urlPattern: Pattern = Patterns.WEB_URL
): List<LinkEntry> =
    messages
        .flatMap { message ->
            findUrls(message.body, urlPattern).map { match ->
                LinkEntry(
                    url = match.url,
                    messageId = message.id,
                    timestamp = message.timestamp,
                    isSent = message.isSent
                )
            }
        }
        .sortedByDescending { it.timestamp }
