package com.plusorminustwo.postmark.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class FontFamilyPreferenceTest {

    @Test
    fun `null falls back to SYSTEM`() {
        assertEquals(FontFamilyPreference.SYSTEM, fontFamilyPreferenceFromString(null))
    }

    @Test
    fun `unknown value falls back to SYSTEM`() {
        assertEquals(FontFamilyPreference.SYSTEM, fontFamilyPreferenceFromString("garbage"))
    }

    @Test
    fun `blank value falls back to SYSTEM`() {
        assertEquals(FontFamilyPreference.SYSTEM, fontFamilyPreferenceFromString(""))
    }

    @Test
    fun `every enum name round-trips`() {
        for (pref in FontFamilyPreference.values()) {
            assertEquals(pref, fontFamilyPreferenceFromString(pref.name))
        }
    }
}
