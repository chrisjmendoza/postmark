package com.plusorminustwo.postmark.domain.customization

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleStylePreferenceTest {

    @Test
    fun `null falls back to ROUNDED`() {
        assertEquals(BubbleStylePreference.ROUNDED, bubbleStylePreferenceFromString(null))
    }

    @Test
    fun `unknown value falls back to ROUNDED`() {
        assertEquals(BubbleStylePreference.ROUNDED, bubbleStylePreferenceFromString("garbage"))
    }

    @Test
    fun `blank value falls back to ROUNDED`() {
        assertEquals(BubbleStylePreference.ROUNDED, bubbleStylePreferenceFromString(""))
    }

    @Test
    fun `every enum name round-trips`() {
        for (pref in BubbleStylePreference.values()) {
            assertEquals(pref, bubbleStylePreferenceFromString(pref.name))
        }
    }
}
