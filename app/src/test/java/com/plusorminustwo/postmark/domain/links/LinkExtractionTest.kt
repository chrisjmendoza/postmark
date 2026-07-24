package com.plusorminustwo.postmark.domain.links

import com.plusorminustwo.postmark.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Tests [extractLinks] (and its shared [findUrls] helper) against the same URL forms
 * `Patterns.WEB_URL` supports inside `linkifyText` (ThreadScreen): scheme-prefixed,
 * `www.`-prefixed, and bare-domain URLs.
 *
 * `android.util.Patterns.WEB_URL`'s field is stubbed to null in a plain (non-Robolectric)
 * unit test, so tests inject [TEST_URL_PATTERN] — a small equivalent fixture — via
 * [extractLinks]'s `urlPattern` parameter. Production code never passes this parameter;
 * it always resolves to the real `Patterns.WEB_URL`, the exact field `linkifyText` uses.
 */
class LinkExtractionTest {

    /** Matches http(s)://, www.-prefixed, and bare `word.tld` forms — enough to exercise
     *  every URL shape these tests care about without depending on the real Android field. */
    private val TEST_URL_PATTERN: Pattern = Pattern.compile(
        "(?:https?://|www\\.)[\\w.-]+(?:/[\\w./?%&=-]*)?" +
            "|" +
            "\\b[a-zA-Z0-9-]+\\.[a-zA-Z]{2,}\\b"
    )

    private fun message(
        id: Long,
        body: String,
        timestamp: Long,
        isSent: Boolean = false
    ) = Message(
        id = id,
        threadId = 1L,
        address = "+15551234567",
        body = body,
        timestamp = timestamp,
        isSent = isSent,
        type = 1
    )

    private fun extract(messages: List<Message>) = extractLinks(messages, TEST_URL_PATTERN)

    @Test fun `no urls yields an empty list`() {
        val messages = listOf(message(1, "just some plain text, no links here", 1_000L))
        assertTrue(extract(messages).isEmpty())
    }

    @Test fun `https url is extracted`() {
        val messages = listOf(message(1, "check this out https://example.com/page", 1_000L))
        val result = extract(messages)
        assertEquals(listOf("https://example.com/page"), result.map { it.url })
    }

    @Test fun `http url is extracted`() {
        val messages = listOf(message(1, "old link: http://example.com/legacy", 1_000L))
        val result = extract(messages)
        assertEquals(listOf("http://example.com/legacy"), result.map { it.url })
    }

    @Test fun `www prefixed url is normalized with a scheme`() {
        val messages = listOf(message(1, "see www.example.com for details", 1_000L))
        val result = extract(messages)
        assertEquals(listOf("http://www.example.com"), result.map { it.url })
    }

    @Test fun `bare domain is normalized with a scheme`() {
        val messages = listOf(message(1, "visit example.com today", 1_000L))
        val result = extract(messages)
        assertEquals(listOf("http://example.com"), result.map { it.url })
    }

    @Test fun `multiple urls in one body are all extracted in text order`() {
        val body = "first https://a.example.com then https://b.example.com and www.c.example.com"
        val messages = listOf(message(1, body, 1_000L))
        val result = extract(messages)
        assertEquals(
            listOf("https://a.example.com", "https://b.example.com", "http://www.c.example.com"),
            result.map { it.url }
        )
    }

    @Test fun `same url sent twice produces two entries, not a dedupe`() {
        val messages = listOf(
            message(1, "https://example.com/thing", 1_000L, isSent = true),
            message(2, "https://example.com/thing", 2_000L, isSent = false)
        )
        val result = extract(messages)
        assertEquals(2, result.size)
        assertTrue(result.all { it.url == "https://example.com/thing" })
    }

    @Test fun `results are ordered newest first regardless of input order`() {
        val messages = listOf(
            message(1, "https://old.example.com", 1_000L),
            message(2, "https://newest.example.com", 3_000L),
            message(3, "https://middle.example.com", 2_000L)
        )
        val result = extract(messages)
        assertEquals(
            listOf("https://newest.example.com", "https://middle.example.com", "https://old.example.com"),
            result.map { it.url }
        )
    }

    @Test fun `each entry carries its source message id, timestamp and sent flag`() {
        val messages = listOf(message(42, "https://example.com", 5_000L, isSent = true))
        val entry = extract(messages).single()
        assertEquals(42L, entry.messageId)
        assertEquals(5_000L, entry.timestamp)
        assertTrue(entry.isSent)
    }
}
