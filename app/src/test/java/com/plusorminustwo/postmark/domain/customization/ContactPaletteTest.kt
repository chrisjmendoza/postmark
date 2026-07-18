package com.plusorminustwo.postmark.domain.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPaletteTest {

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
    fun `bubbleContainerColor is deterministic`() {
        val accent = ContactPalette.colors.first().argb
        val a = ContactPalette.bubbleContainerColor(accent, isDark = true)
        val b = ContactPalette.bubbleContainerColor(accent, isDark = true)
        assertEquals(a, b)
    }

    @Test
    fun `onBubbleContentColor is deterministic`() {
        val accent = ContactPalette.colors.first().argb
        val a = ContactPalette.onBubbleContentColor(accent, isDark = false)
        val b = ContactPalette.onBubbleContentColor(accent, isDark = false)
        assertEquals(a, b)
    }

    // ── Dark theme: raw accent is the content color (mirrors onPrimaryContainer) ───

    @Test
    fun `dark theme content color is the raw accent`() {
        for (color in ContactPalette.colors) {
            assertEquals(
                "content color mismatch for ${color.name}",
                color.argb,
                ContactPalette.onBubbleContentColor(color.argb, isDark = true)
            )
        }
    }

    // ── Container darker/lighter invariants ─────────────────────────────────────

    @Test
    fun `dark container is darker than the raw accent for every palette color`() {
        for (color in ContactPalette.colors) {
            val container = ContactPalette.bubbleContainerColor(color.argb, isDark = true)
            assertTrue(
                "${color.name}: dark container should be darker than the raw accent",
                ContactPalette.relativeLuminance(container) < ContactPalette.relativeLuminance(color.argb)
            )
        }
    }

    @Test
    fun `light container is lighter than the raw accent for every palette color`() {
        for (color in ContactPalette.colors) {
            val container = ContactPalette.bubbleContainerColor(color.argb, isDark = false)
            assertTrue(
                "${color.name}: light container should be lighter than the raw accent",
                ContactPalette.relativeLuminance(container) > ContactPalette.relativeLuminance(color.argb)
            )
        }
    }

    // ── Legibility: WCAG contrast >= 3.0 for every palette color, both themes ──────

    @Test
    fun `dark theme container vs content contrast is at least 3 for every palette color`() {
        for (color in ContactPalette.colors) {
            val container = ContactPalette.bubbleContainerColor(color.argb, isDark = true)
            val content = ContactPalette.onBubbleContentColor(color.argb, isDark = true)
            val ratio = ContactPalette.contrastRatio(container, content)
            assertTrue(
                "${color.name}: dark contrast $ratio below 3.0",
                ratio >= 3.0
            )
        }
    }

    @Test
    fun `light theme container vs content contrast is at least 3 for every palette color`() {
        for (color in ContactPalette.colors) {
            val container = ContactPalette.bubbleContainerColor(color.argb, isDark = false)
            val content = ContactPalette.onBubbleContentColor(color.argb, isDark = false)
            val ratio = ContactPalette.contrastRatio(container, content)
            assertTrue(
                "${color.name}: light contrast $ratio below 3.0",
                ratio >= 3.0
            )
        }
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
