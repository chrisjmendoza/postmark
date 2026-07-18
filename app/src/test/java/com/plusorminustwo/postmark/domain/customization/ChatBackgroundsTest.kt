package com.plusorminustwo.postmark.domain.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackgroundsTest {

    // ── Catalog shape ────────────────────────────────────────────────────────────

    @Test
    fun `catalog has None plus 6 curated backgrounds`() {
        assertEquals(7, ChatBackgrounds.all.size)
    }

    @Test
    fun `catalog includes None`() {
        assertTrue(ChatBackgrounds.all.contains(ChatBackgrounds.None))
    }

    @Test
    fun `ids are unique`() {
        val ids = ChatBackgrounds.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `display names are unique`() {
        val names = ChatBackgrounds.all.map { it.displayName }
        assertEquals(names.size, names.distinct().size)
    }

    // ── resolve() ─────────────────────────────────────────────────────────────────

    @Test
    fun `resolve round-trips every catalog entry by id`() {
        for (background in ChatBackgrounds.all) {
            assertEquals(background, ChatBackgrounds.resolve(background.id))
        }
    }

    @Test
    fun `resolve of null falls back to None`() {
        assertEquals(ChatBackgrounds.None, ChatBackgrounds.resolve(null))
    }

    @Test
    fun `resolve of an unknown id falls back to None`() {
        assertEquals(ChatBackgrounds.None, ChatBackgrounds.resolve("garbage"))
    }

    // ── Stop-list shape ──────────────────────────────────────────────────────────

    @Test
    fun `None has empty color lists`() {
        assertTrue(ChatBackgrounds.None.darkColorsArgb.isEmpty())
        assertTrue(ChatBackgrounds.None.lightColorsArgb.isEmpty())
    }

    @Test
    fun `every non-None background has equal-length non-empty dark and light stop lists`() {
        for (background in ChatBackgrounds.all - ChatBackgrounds.None) {
            assertTrue("${background.id}: dark stops empty", background.darkColorsArgb.isNotEmpty())
            assertTrue("${background.id}: light stops empty", background.lightColorsArgb.isNotEmpty())
            assertEquals(
                "${background.id}: dark/light stop count mismatch",
                background.darkColorsArgb.size,
                background.lightColorsArgb.size
            )
        }
    }

    // ── Legibility guard: stops stay close to the theme background's luminance ─────
    // Reuses ContactPalette.relativeLuminance (Phase B) rather than duplicating the
    // WCAG math here.

    @Test
    fun `every dark-variant stop stays in a dark luminance band`() {
        for (background in ChatBackgrounds.all - ChatBackgrounds.None) {
            for (argb in background.darkColorsArgb) {
                val luminance = ContactPalette.relativeLuminance(argb)
                assertTrue(
                    "${background.id}: dark stop luminance $luminance not < 0.15",
                    luminance < 0.15
                )
            }
        }
    }

    @Test
    fun `every light-variant stop stays in a light luminance band`() {
        for (background in ChatBackgrounds.all - ChatBackgrounds.None) {
            for (argb in background.lightColorsArgb) {
                val luminance = ContactPalette.relativeLuminance(argb)
                assertTrue(
                    "${background.id}: light stop luminance $luminance not > 0.7",
                    luminance > 0.7
                )
            }
        }
    }

    // ── Global preference id normalization ──────────────────────────────────────
    // The global chat-background preference has no "Default"/follow-global concept,
    // so None.id collapses to null (its own "unset") on the way in, and null expands
    // back to None.id on the way out — see toGlobalPreferenceId/fromGlobalPreferenceId.

    @Test
    fun `toGlobalPreferenceId collapses None's id to null`() {
        assertEquals(null, ChatBackgrounds.toGlobalPreferenceId(ChatBackgrounds.None.id))
    }

    @Test
    fun `toGlobalPreferenceId passes other ids and null through unchanged`() {
        assertEquals(null, ChatBackgrounds.toGlobalPreferenceId(null))
        for (background in ChatBackgrounds.all - ChatBackgrounds.None) {
            assertEquals(background.id, ChatBackgrounds.toGlobalPreferenceId(background.id))
        }
    }

    @Test
    fun `fromGlobalPreferenceId expands null to None's id`() {
        assertEquals(ChatBackgrounds.None.id, ChatBackgrounds.fromGlobalPreferenceId(null))
    }

    @Test
    fun `toGlobalPreferenceId and fromGlobalPreferenceId round-trip every catalog id`() {
        for (background in ChatBackgrounds.all) {
            val stored = ChatBackgrounds.toGlobalPreferenceId(background.id)
            assertEquals(background.id, ChatBackgrounds.fromGlobalPreferenceId(stored))
        }
    }
}
