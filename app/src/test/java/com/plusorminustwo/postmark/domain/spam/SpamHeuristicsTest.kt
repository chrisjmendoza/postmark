package com.plusorminustwo.postmark.domain.spam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JUnit coverage for the conservative spam heuristic and the banner-visibility rule.
 * No Android, no Mockito/MockK/Turbine — [looksLikeSpam] and [shouldShowSpamBanner] are
 * ordinary functions.
 */
class SpamHeuristicsTest {

    // ── looksLikeSpam: the positive case ──────────────────────────────────────

    @Test
    fun `unknown sender short body with https url is spam`() {
        assertTrue(
            looksLikeSpam(
                body = "You won! Claim at https://claim-now.example/prize",
                senderIsKnownContact = false,
                isGroupThread = false
            )
        )
    }

    @Test
    fun `www form url is spam`() {
        assertTrue(
            looksLikeSpam("Update your account www.paypa1-secure.com now", false, false)
        )
    }

    @Test
    fun `bare domain-tld form is spam`() {
        assertTrue(
            looksLikeSpam("Track your parcel at fedex-track.info today", false, false)
        )
    }

    // ── looksLikeSpam: the guards that must return false ──────────────────────

    @Test
    fun `known contact is never spam even with a url`() {
        assertFalse(
            looksLikeSpam("check this out example.com", senderIsKnownContact = true, isGroupThread = false)
        )
    }

    @Test
    fun `group thread is never spam even with a url`() {
        assertFalse(
            looksLikeSpam("party pics at photos.example.com", senderIsKnownContact = false, isGroupThread = true)
        )
    }

    @Test
    fun `long body with a url is not flagged`() {
        val longBody = "Hi there, " + "just catching up on everything. ".repeat(10) +
            "anyway here is the link example.com and lots more text after it to push us well past the limit."
        assertTrue(longBody.length >= SPAM_MAX_BODY_LENGTH)
        assertFalse(looksLikeSpam(longBody, false, false))
    }

    @Test
    fun `short body without a url is not flagged`() {
        assertFalse(looksLikeSpam("Hey are we still on for lunch tomorrow?", false, false))
    }

    @Test
    fun `empty body is not flagged`() {
        assertFalse(looksLikeSpam("", false, false))
    }

    @Test
    fun `blank body is not flagged`() {
        assertFalse(looksLikeSpam("     ", false, false))
    }

    @Test
    fun `plain sentence with abbreviation dot is not flagged`() {
        // "3.30pm" and "U.S.A" style period usage must not read as a bare domain.
        assertFalse(looksLikeSpam("See you at 3.30pm in the U.S.A", false, false))
    }

    // ── shouldShowSpamBanner ──────────────────────────────────────────────────

    @Test
    fun `banner shows when suspect and not spam and not dismissed`() {
        assertTrue(shouldShowSpamBanner(suspect = true, isSpam = false, dismissed = false))
    }

    @Test
    fun `banner hidden when not suspect`() {
        assertFalse(shouldShowSpamBanner(suspect = false, isSpam = false, dismissed = false))
    }

    @Test
    fun `banner hidden when already marked spam`() {
        assertFalse(shouldShowSpamBanner(suspect = true, isSpam = true, dismissed = false))
    }

    @Test
    fun `banner hidden when dismissed`() {
        assertFalse(shouldShowSpamBanner(suspect = true, isSpam = false, dismissed = true))
    }
}
