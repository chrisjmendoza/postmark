package com.plusorminustwo.postmark.domain.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPaletteTest {

    // Theme background constants (dark #1C1C1E / light #F2F2F7), mirroring AppAccentTest
    // and ColorMathTest, so deriveAccentPair's background guard is exercised against the
    // real app surfaces rather than an arbitrary color.
    private val darkThemeBg = 0xFF1C1C1E.toInt()
    private val lightThemeBg = 0xFFF2F2F7.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // ── Palette shape ────────────────────────────────────────────────────────────

    @Test
    fun `palette has 12 entries`() {
        assertEquals(12, ContactPalette.colors.size)
    }

    @Test
    fun `palette entries have distinct ARGB values`() {
        val distinctArgb = ContactPalette.colors.map { it.argb }.distinct()
        assertEquals(ContactPalette.colors.size, distinctArgb.size)
    }

    @Test
    fun `palette entries have distinct names`() {
        val distinctNames = ContactPalette.colors.map { it.name }.distinct()
        assertEquals(ContactPalette.colors.size, distinctNames.size)
    }

    @Test
    fun `palette includes a color close to the app accent blue`() {
        val appAccentBlue = 0xFF378ADD.toInt()
        assertTrue(ContactPalette.colors.any { it.argb == appAccentBlue })
    }

    // ── Determinism ───────────────────────────────────────────────────────────────

    @Test
    fun `onBubbleContentColor is deterministic`() {
        val accent = ContactPalette.colors.first().argb
        val a = ContactPalette.onBubbleContentColor(accent, isDark = false)
        val b = ContactPalette.onBubbleContentColor(accent, isDark = false)
        assertEquals(a, b)
    }

    @Test
    fun `deriveAccentPair is deterministic`() {
        val accent = ContactPalette.colors.first().argb
        val a = ContactPalette.deriveAccentPair(accent, darkThemeBg, isDark = true)
        val b = ContactPalette.deriveAccentPair(accent, darkThemeBg, isDark = true)
        assertEquals(a, b)
    }

    // ── onBubbleContentColor is exactly white or black ──────────────────────────

    @Test
    fun `content color is exactly white or black for every palette color, both themes`() {
        for (color in ContactPalette.colors) {
            for (isDark in listOf(true, false)) {
                val content = ContactPalette.onBubbleContentColor(color.argb, isDark)
                assertTrue(
                    "${color.name} (isDark=$isDark): content $content is neither pure white nor pure black",
                    content == white || content == black
                )
            }
        }
    }

    // ── deriveAccentPair ─────────────────────────────────────────────────────────
    // The container is adjustAccentForBackground(accent, bg) — the identity for every
    // preset against both theme backgrounds — and the content is white/black-by-contrast.
    // This folds in the old "container == accent" guarantee (bubbleContainerColor, removed)
    // and the >= 4.5 content-vs-container floor.

    @Test
    fun `deriveAccentPair container equals the raw accent for every preset on both theme backgrounds`() {
        for (color in ContactPalette.colors) {
            for (bg in listOf(darkThemeBg to true, lightThemeBg to false)) {
                val pair = ContactPalette.deriveAccentPair(color.argb, bg.first, bg.second)
                assertEquals(
                    "${color.name} (isDark=${bg.second}): container should equal the raw accent",
                    color.argb,
                    pair.containerArgb
                )
            }
        }
    }

    @Test
    fun `deriveAccentPair content is white or black and clears the 4point5 floor for every preset`() {
        for (color in ContactPalette.colors) {
            for (bg in listOf(darkThemeBg to true, lightThemeBg to false)) {
                val pair = ContactPalette.deriveAccentPair(color.argb, bg.first, bg.second)
                assertTrue(
                    "${color.name} (isDark=${bg.second}): content is neither white nor black",
                    pair.contentArgb == white || pair.contentArgb == black
                )
                assertTrue(
                    "${color.name} (isDark=${bg.second}): contrast below 4.5",
                    ContactPalette.contrastRatio(pair.containerArgb, pair.contentArgb) >= 4.5
                )
            }
        }
    }

    @Test
    fun `deriveAccentPair nudges a pathological accent that matches the background`() {
        // An accent equal to the theme background would vanish; the container is nudged
        // so it differs and clears the 1.3 background-contrast bar, in both themes.
        val dark = ContactPalette.deriveAccentPair(darkThemeBg, darkThemeBg, isDark = true)
        assertNotEquals(darkThemeBg, dark.containerArgb)
        assertTrue(ContactPalette.contrastRatio(dark.containerArgb, darkThemeBg) >= 1.3)

        val light = ContactPalette.deriveAccentPair(lightThemeBg, lightThemeBg, isDark = false)
        assertNotEquals(lightThemeBg, light.containerArgb)
        assertTrue(ContactPalette.contrastRatio(light.containerArgb, lightThemeBg) >= 1.3)
    }

    // ── resolveThreadBubbleColors ─────────────────────────────────────────────────
    // Reproduces ThreadContent's fallback chain: received pair from accentColorArgb,
    // sent pair from sentColorArgb, and sentContent falling back to the app-accent
    // onPrimaryContainer color only when appAccentArgb is set AND the thread has no sent
    // color of its own.

    private val blue = ContactPalette.colors.first { it.name == "Blue" }.argb
    private val appAccentOnPrimary = 0xFF123456.toInt()

    @Test
    fun `resolveThreadBubbleColors with everything unset leaves all four colors null`() {
        val r = ContactPalette.resolveThreadBubbleColors(
            accentColorArgb = null, sentColorArgb = null, appAccentArgb = null,
            appAccentOnPrimaryContainerArgb = appAccentOnPrimary, isDark = true, backgroundArgb = darkThemeBg
        )
        assertNull(r.receivedContainerArgb)
        assertNull(r.receivedContentArgb)
        assertNull(r.sentContainerArgb)
        assertNull(r.sentContentArgb)
    }

    @Test
    fun `resolveThreadBubbleColors with only an accent sets the received pair and leaves sent null`() {
        val r = ContactPalette.resolveThreadBubbleColors(
            accentColorArgb = blue, sentColorArgb = null, appAccentArgb = null,
            appAccentOnPrimaryContainerArgb = appAccentOnPrimary, isDark = true, backgroundArgb = darkThemeBg
        )
        val pair = ContactPalette.deriveAccentPair(blue, darkThemeBg, isDark = true)
        assertEquals(pair.containerArgb, r.receivedContainerArgb)
        assertEquals(pair.contentArgb, r.receivedContentArgb)
        assertNull(r.sentContainerArgb)
        assertNull(r.sentContentArgb)
    }

    @Test
    fun `resolveThreadBubbleColors with only a sent color sets the sent pair and leaves received null`() {
        val r = ContactPalette.resolveThreadBubbleColors(
            accentColorArgb = null, sentColorArgb = blue, appAccentArgb = null,
            appAccentOnPrimaryContainerArgb = appAccentOnPrimary, isDark = true, backgroundArgb = darkThemeBg
        )
        val pair = ContactPalette.deriveAccentPair(blue, darkThemeBg, isDark = true)
        assertNull(r.receivedContainerArgb)
        assertNull(r.receivedContentArgb)
        assertEquals(pair.containerArgb, r.sentContainerArgb)
        assertEquals(pair.contentArgb, r.sentContentArgb)
    }

    @Test
    fun `resolveThreadBubbleColors sentContent falls back to the app accent only when no sent color is set`() {
        // App accent set, no sent color → sentContent is the fallback, sentContainer null.
        val fallbackFires = ContactPalette.resolveThreadBubbleColors(
            accentColorArgb = null, sentColorArgb = null, appAccentArgb = blue,
            appAccentOnPrimaryContainerArgb = appAccentOnPrimary, isDark = true, backgroundArgb = darkThemeBg
        )
        assertNull(fallbackFires.sentContainerArgb)
        assertEquals(appAccentOnPrimary, fallbackFires.sentContentArgb)

        // App accent set AND a sent color set → the sent pair wins, no fallback.
        val sentWins = ContactPalette.resolveThreadBubbleColors(
            accentColorArgb = null, sentColorArgb = blue, appAccentArgb = blue,
            appAccentOnPrimaryContainerArgb = appAccentOnPrimary, isDark = true, backgroundArgb = darkThemeBg
        )
        val pair = ContactPalette.deriveAccentPair(blue, darkThemeBg, isDark = true)
        assertEquals(pair.contentArgb, sentWins.sentContentArgb)
        assertNotEquals(appAccentOnPrimary, sentWins.sentContentArgb)

        // No app accent, no sent color → no fallback at all.
        val noFallback = ContactPalette.resolveThreadBubbleColors(
            accentColorArgb = null, sentColorArgb = null, appAccentArgb = null,
            appAccentOnPrimaryContainerArgb = appAccentOnPrimary, isDark = true, backgroundArgb = darkThemeBg
        )
        assertNull(noFallback.sentContentArgb)
    }

    @Test
    fun `resolveThreadBubbleColors applies the background guard to both directions`() {
        // A pathological accent equal to the background, used for both received and sent,
        // is nudged in each direction (the guard is applied per pair, not once).
        val r = ContactPalette.resolveThreadBubbleColors(
            accentColorArgb = darkThemeBg, sentColorArgb = darkThemeBg, appAccentArgb = null,
            appAccentOnPrimaryContainerArgb = appAccentOnPrimary, isDark = true, backgroundArgb = darkThemeBg
        )
        assertNotEquals(darkThemeBg, r.receivedContainerArgb)
        assertNotEquals(darkThemeBg, r.sentContainerArgb)
    }

    // ── contrastRatio sanity ─────────────────────────────────────────────────────

    @Test
    fun `contrastRatio of black and white is 21`() {
        val ratio = ContactPalette.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(21.0, ratio, 0.01)
    }

    @Test
    fun `contrastRatio is symmetric`() {
        val a = 0xFF378ADD.toInt()
        val b = 0xFF1A3A5C.toInt()
        assertEquals(ContactPalette.contrastRatio(a, b), ContactPalette.contrastRatio(b, a), 0.0001)
    }

    @Test
    fun `contrastRatio of identical colors is 1`() {
        val a = 0xFF378ADD.toInt()
        assertEquals(1.0, ContactPalette.contrastRatio(a, a), 0.0001)
    }
}
