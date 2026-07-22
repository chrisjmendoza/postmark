package com.plusorminustwo.postmark.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.plusorminustwo.postmark.domain.customization.ColorMath
import com.plusorminustwo.postmark.domain.customization.ContactPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [applyAppAccent] (Phase I global app accent). `ColorScheme` is a
 * plain `@Immutable` class with `val` properties, a structural `copy()`, and no
 * Android-framework calls anywhere in its construction — and `material3` sits on
 * this module's `implementation` configuration, which Gradle's `testImplementation`
 * extends, so it (and its `foundation`/`ui` transitives) are on the JVM unit-test
 * classpath the same way `BubbleShapeStyleTest` already relies on for
 * `RoundedCornerShape`. No Robolectric needed.
 *
 * Test schemes reuse the app's own brand background literals (dark `#1C1C1E`, light
 * `#F2F2F7` — the same ones `ColorMathTest` already duplicates for its background
 * guard tests) rather than reaching into `Theme.kt`'s private `DarkColorScheme`/
 * `LightColorScheme` vals, so this file has no dependency on anything but the
 * function under test.
 */
class AppAccentTest {

    private val darkThemeBg = 0xFF1C1C1E.toInt()
    private val lightThemeBg = 0xFFF2F2F7.toInt()

    private val sampleDarkScheme = darkColorScheme(background = Color(darkThemeBg))
    private val sampleLightScheme = lightColorScheme(background = Color(lightThemeBg))

    // Matches ContactPalette's "Blue" preset, which is itself the app's own brand accent.
    private val blueAccent = ContactPalette.colors.first { it.name == "Blue" }.argb

    // ── Primary family replaced with the accent-derived colors ─────────────────────

    @Test
    fun `dark scheme primary family becomes the accent-derived colors`() {
        val result = applyAppAccent(sampleDarkScheme, blueAccent, isDark = true)

        val expectedAccentArgb = ColorMath.adjustAccentForBackground(blueAccent, darkThemeBg)
        val expectedContentArgb = ContactPalette.onBubbleContentColor(expectedAccentArgb, true)

        assertEquals(Color(expectedAccentArgb), result.primary)
        assertEquals(Color(expectedContentArgb), result.onPrimary)
        assertEquals(Color(expectedAccentArgb), result.primaryContainer)
        assertEquals(Color(expectedContentArgb), result.onPrimaryContainer)
    }

    @Test
    fun `light scheme primary family becomes the accent-derived colors`() {
        val result = applyAppAccent(sampleLightScheme, blueAccent, isDark = false)

        val expectedAccentArgb = ColorMath.adjustAccentForBackground(blueAccent, lightThemeBg)
        val expectedContentArgb = ContactPalette.onBubbleContentColor(expectedAccentArgb, false)

        assertEquals(Color(expectedAccentArgb), result.primary)
        assertEquals(Color(expectedContentArgb), result.onPrimary)
        assertEquals(Color(expectedAccentArgb), result.primaryContainer)
        assertEquals(Color(expectedContentArgb), result.onPrimaryContainer)
    }

    // ── Everything else untouched ───────────────────────────────────────────────────

    @Test
    fun `non-primary roles are untouched, dark scheme`() {
        assertNonPrimaryRolesUnchanged(sampleDarkScheme, applyAppAccent(sampleDarkScheme, blueAccent, isDark = true))
    }

    @Test
    fun `non-primary roles are untouched, light scheme`() {
        assertNonPrimaryRolesUnchanged(sampleLightScheme, applyAppAccent(sampleLightScheme, blueAccent, isDark = false))
    }

    private fun assertNonPrimaryRolesUnchanged(input: ColorScheme, result: ColorScheme) {
        assertEquals(input.secondary, result.secondary)
        assertEquals(input.onSecondary, result.onSecondary)
        assertEquals(input.secondaryContainer, result.secondaryContainer)
        assertEquals(input.onSecondaryContainer, result.onSecondaryContainer)
        assertEquals(input.tertiary, result.tertiary)
        assertEquals(input.onTertiary, result.onTertiary)
        assertEquals(input.tertiaryContainer, result.tertiaryContainer)
        assertEquals(input.onTertiaryContainer, result.onTertiaryContainer)
        assertEquals(input.background, result.background)
        assertEquals(input.onBackground, result.onBackground)
        assertEquals(input.surface, result.surface)
        assertEquals(input.onSurface, result.onSurface)
        assertEquals(input.surfaceVariant, result.surfaceVariant)
        assertEquals(input.onSurfaceVariant, result.onSurfaceVariant)
        assertEquals(input.error, result.error)
        assertEquals(input.onError, result.onError)
        assertEquals(input.errorContainer, result.errorContainer)
        assertEquals(input.onErrorContainer, result.onErrorContainer)
        assertEquals(input.outline, result.outline)
        assertEquals(input.outlineVariant, result.outlineVariant)
        assertEquals(input.scrim, result.scrim)
        assertEquals(input.inverseSurface, result.inverseSurface)
        assertEquals(input.inverseOnSurface, result.inverseOnSurface)
    }

    // ── White-vs-black content pick mirrors ContactPalette, for every preset ───────

    @Test
    fun `onPrimary and onPrimaryContainer are exactly white or black for every preset, both themes`() {
        val white = Color(0xFFFFFFFF.toInt())
        val black = Color(0xFF000000.toInt())
        for (color in ContactPalette.colors) {
            val darkResult = applyAppAccent(sampleDarkScheme, color.argb, isDark = true)
            val lightResult = applyAppAccent(sampleLightScheme, color.argb, isDark = false)
            for (result in listOf(darkResult, lightResult)) {
                assertTrue(
                    "${color.name}: onPrimary should be pure white or pure black",
                    result.onPrimary == white || result.onPrimary == black
                )
                // onPrimary and onPrimaryContainer are derived identically (both are the
                // content color for the same accent fill), so they must always match.
                assertEquals(result.onPrimary, result.onPrimaryContainer)
            }
        }
    }

    // ── Pathological accent (== the scheme's own background) never vanishes ────────

    @Test
    fun `accent matching the dark scheme's background is nudged to stay visible`() {
        val result = applyAppAccent(sampleDarkScheme, darkThemeBg, isDark = true)
        assertNotEquals(sampleDarkScheme.background, result.primary)
        assertTrue(
            ContactPalette.contrastRatio(result.primary.toArgb(), sampleDarkScheme.background.toArgb()) >= 1.3
        )
    }

    @Test
    fun `accent matching the light scheme's background is nudged to stay visible`() {
        val result = applyAppAccent(sampleLightScheme, lightThemeBg, isDark = false)
        assertNotEquals(sampleLightScheme.background, result.primary)
        assertTrue(
            ContactPalette.contrastRatio(result.primary.toArgb(), sampleLightScheme.background.toArgb()) >= 1.3
        )
    }

    // ── inversePrimary is derived against the OTHER theme's background ─────────────

    @Test
    fun `dark scheme inversePrimary is guarded against the light background`() {
        val result = applyAppAccent(sampleDarkScheme, blueAccent, isDark = true)
        val expected = ColorMath.adjustAccentForBackground(blueAccent, lightThemeBg)
        assertEquals(Color(expected), result.inversePrimary)
    }

    @Test
    fun `light scheme inversePrimary is guarded against the dark background`() {
        val result = applyAppAccent(sampleLightScheme, blueAccent, isDark = false)
        val expected = ColorMath.adjustAccentForBackground(blueAccent, darkThemeBg)
        assertEquals(Color(expected), result.inversePrimary)
    }

    // ── Determinism ──────────────────────────────────────────────────────────────

    @Test
    fun `applyAppAccent is deterministic`() {
        val a = applyAppAccent(sampleDarkScheme, blueAccent, isDark = true)
        val b = applyAppAccent(sampleDarkScheme, blueAccent, isDark = true)
        assertEquals(a.primary, b.primary)
        assertEquals(a.onPrimary, b.onPrimary)
        assertEquals(a.primaryContainer, b.primaryContainer)
        assertEquals(a.onPrimaryContainer, b.onPrimaryContainer)
        assertEquals(a.inversePrimary, b.inversePrimary)
    }
}
