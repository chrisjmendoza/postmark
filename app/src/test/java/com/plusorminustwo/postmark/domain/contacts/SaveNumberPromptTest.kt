package com.plusorminustwo.postmark.domain.contacts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JUnit coverage for the "Add to contacts?" banner rule. No Android, no Mockito/MockK/
 * Turbine — [isSaveablePhoneNumber] and [shouldShowSaveNumberPrompt] are ordinary functions.
 */
class SaveNumberPromptTest {

    // ── isSaveablePhoneNumber ──────────────────────────────────────────────────

    @Test
    fun `plain 10-digit number is saveable`() {
        assertTrue(isSaveablePhoneNumber("2065550100"))
    }

    @Test
    fun `formatted number with punctuation and country code is saveable`() {
        assertTrue(isSaveablePhoneNumber("+1 (555) 123-4567"))
    }

    @Test
    fun `5-digit shortcode is not saveable`() {
        assertFalse(isSaveablePhoneNumber("55555"))
    }

    @Test
    fun `alphanumeric sender id is not saveable`() {
        assertFalse(isSaveablePhoneNumber("AMAZON"))
    }

    // ── shouldShowSaveNumberPrompt ─────────────────────────────────────────────

    @Test
    fun `shows for a normal unknown 10-digit number`() {
        assertTrue(
            shouldShowSaveNumberPrompt(
                isGroup = false,
                contactName = null,
                address = "2065550100",
                dismissed = false,
                spamBannerVisible = false
            )
        )
    }

    @Test
    fun `hidden for a known contact`() {
        assertFalse(
            shouldShowSaveNumberPrompt(
                isGroup = false,
                contactName = "Jamie Rivera",
                address = "2065550100",
                dismissed = false,
                spamBannerVisible = false
            )
        )
    }

    @Test
    fun `hidden for a group thread`() {
        assertFalse(
            shouldShowSaveNumberPrompt(
                isGroup = true,
                contactName = null,
                address = "2065550100",
                dismissed = false,
                spamBannerVisible = false
            )
        )
    }

    @Test
    fun `hidden for a 5-digit shortcode`() {
        assertFalse(
            shouldShowSaveNumberPrompt(
                isGroup = false,
                contactName = null,
                address = "55555",
                dismissed = false,
                spamBannerVisible = false
            )
        )
    }

    @Test
    fun `hidden for an alphanumeric sender`() {
        assertFalse(
            shouldShowSaveNumberPrompt(
                isGroup = false,
                contactName = null,
                address = "AMAZON",
                dismissed = false,
                spamBannerVisible = false
            )
        )
    }

    @Test
    fun `hidden once dismissed`() {
        assertFalse(
            shouldShowSaveNumberPrompt(
                isGroup = false,
                contactName = null,
                address = "2065550100",
                dismissed = true,
                spamBannerVisible = false
            )
        )
    }

    @Test
    fun `hidden while the spam banner is visible`() {
        assertFalse(
            shouldShowSaveNumberPrompt(
                isGroup = false,
                contactName = null,
                address = "2065550100",
                dismissed = false,
                spamBannerVisible = true
            )
        )
    }

    @Test
    fun `shows for a formatted plausible number`() {
        assertTrue(
            shouldShowSaveNumberPrompt(
                isGroup = false,
                contactName = null,
                address = "+1 (555) 123-4567",
                dismissed = false,
                spamBannerVisible = false
            )
        )
    }
}
