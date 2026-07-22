package com.plusorminustwo.postmark.domain.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorMathTest {

    // ── HSV <-> ARGB round-trip ──────────────────────────────────────────────────

    private fun assertChannelsClose(expected: Int, actual: Int, tolerance: Int = 1) {
        val er = (expected ushr 16) and 0xFF
        val eg = (expected ushr 8) and 0xFF
        val eb = expected and 0xFF
        val ar = (actual ushr 16) and 0xFF
        val ag = (actual ushr 8) and 0xFF
        val ab = actual and 0xFF
        assertTrue(
            "expected #%06X, got #%06X (tolerance $tolerance)".format(expected and 0xFFFFFF, actual and 0xFFFFFF),
            abs(er - ar) <= tolerance && abs(eg - ag) <= tolerance && abs(eb - ab) <= tolerance
        )
    }

    @Test
    fun `argb to hsv to argb round-trips within rounding for a spread of colors`() {
        val colors = listOf(
            0xFF000000.toInt(), // black
            0xFFFFFFFF.toInt(), // white
            0xFF808080.toInt(), // mid grey
            0xFFC0C0C0.toInt(), // light grey
            0xFFFF0000.toInt(), // red
            0xFF00FF00.toInt(), // green
            0xFF0000FF.toInt(), // blue
            0xFFFFFF00.toInt(), // yellow
            0xFF00FFFF.toInt(), // cyan
            0xFFFF00FF.toInt(), // magenta
            0xFF378ADD.toInt(), // app blue
            0xFFA47C5B.toInt()  // brown preset
        )
        for (argb in colors) {
            val (h, s, v) = ColorMath.argbToHsv(argb)
            val roundTripped = ColorMath.hsvToArgb(h, s, v)
            assertChannelsClose(argb, roundTripped)
        }
    }

    @Test
    fun `hsvToArgb wraps hue modulo 360`() {
        assertEquals(ColorMath.hsvToArgb(10f, 1f, 1f), ColorMath.hsvToArgb(370f, 1f, 1f))
        assertEquals(ColorMath.hsvToArgb(10f, 1f, 1f), ColorMath.hsvToArgb(-350f, 1f, 1f))
    }

    @Test
    fun `hsvToArgb coerces out-of-range saturation and value`() {
        assertEquals(ColorMath.hsvToArgb(200f, 0f, 0f), ColorMath.hsvToArgb(200f, -5f, -5f))
        assertEquals(ColorMath.hsvToArgb(200f, 1f, 1f), ColorMath.hsvToArgb(200f, 5f, 5f))
    }

    @Test
    fun `hsvToArgb is always opaque`() {
        val argb = ColorMath.hsvToArgb(120f, 0.5f, 0.5f)
        assertEquals(0xFF, (argb ushr 24) and 0xFF)
    }

    @Test
    fun `hue 0 saturation 0 and value 0 all produce black`() {
        assertEquals(0xFF000000.toInt(), ColorMath.hsvToArgb(0f, 0f, 0f))
    }

    @Test
    fun `value 1 with zero saturation produces pure white`() {
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.hsvToArgb(0f, 0f, 1f))
    }

    // ── Hex parse ─────────────────────────────────────────────────────────────────

    @Test
    fun `parseHexColor accepts valid inputs`() {
        val cases = listOf(
            "#FF0000" to 0xFFFF0000.toInt(),
            "FF0000" to 0xFFFF0000.toInt(),
            "#ff0000" to 0xFFFF0000.toInt(),
            "ff0000" to 0xFFFF0000.toInt(),
            "#123ABC" to 0xFF123ABC.toInt(),
            "abcdef" to 0xFFABCDEF.toInt(),
            "#000000" to 0xFF000000.toInt(),
            "FFFFFF" to 0xFFFFFFFF.toInt(),
            "  #FF0000  " to 0xFFFF0000.toInt() // surrounding whitespace tolerated
        )
        for ((input, expected) in cases) {
            assertEquals("input '$input'", expected, ColorMath.parseHexColor(input))
        }
    }

    @Test
    fun `parseHexColor rejects invalid inputs`() {
        val cases = listOf(
            "",
            "#",
            "12345",       // too short
            "1234567",     // too long
            "GGGGGG",      // non-hex characters
            "#12345Z",
            "abc",         // 3-digit shorthand not accepted in v1
            "#abc",
            "12345678",    // 8-digit "#AARRGGBB" not accepted in v1
            "#12345678",
            "not a color",
            "#FF00 0"
        )
        for (input in cases) {
            assertNull("input '$input' should be rejected", ColorMath.parseHexColor(input))
        }
    }

    // ── Hex format ────────────────────────────────────────────────────────────────

    @Test
    fun `formatHexColor produces uppercase 7-char hash-prefixed string`() {
        assertEquals("#FF0000", ColorMath.formatHexColor(0xFFFF0000.toInt()))
        assertEquals("#00FF00", ColorMath.formatHexColor(0xFF00FF00.toInt()))
        assertEquals("#ABCDEF", ColorMath.formatHexColor(0xFFABCDEF.toInt()))
    }

    @Test
    fun `formatHexColor ignores alpha`() {
        assertEquals(
            ColorMath.formatHexColor(0xFF123456.toInt()),
            ColorMath.formatHexColor(0x00123456)
        )
    }

    @Test
    fun `formatHexColor and parseHexColor round-trip for opaque colors`() {
        val colors = ContactPalette.colors.map { it.argb } + listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        for (argb in colors) {
            assertEquals(argb, ColorMath.parseHexColor(ColorMath.formatHexColor(argb)))
        }
    }

    // ── adjustAccentForBackground ────────────────────────────────────────────────

    private val darkThemeBg = 0xFF1C1C1E.toInt()
    private val lightThemeBg = 0xFFF2F2F7.toInt()

    @Test
    fun `near-white accent on light background is darkened to at least 1point3 contrast`() {
        val nearWhite = 0xFFFEFEFE.toInt()
        // Sanity: baseline contrast is below the floor, so an adjustment must happen.
        assertTrue(ContactPalette.contrastRatio(nearWhite, lightThemeBg) < 1.3)

        val adjusted = ColorMath.adjustAccentForBackground(nearWhite, lightThemeBg)
        assertNotEquals(nearWhite, adjusted)
        assertTrue(ContactPalette.contrastRatio(adjusted, lightThemeBg) >= 1.3)
        // Darkened, not lightened.
        assertTrue(ContactPalette.relativeLuminance(adjusted) < ContactPalette.relativeLuminance(nearWhite))
    }

    @Test
    fun `near-black accent on dark background is lightened to at least 1point3 contrast`() {
        val nearBlack = 0xFF010101.toInt()
        assertTrue(ContactPalette.contrastRatio(nearBlack, darkThemeBg) < 1.3)

        val adjusted = ColorMath.adjustAccentForBackground(nearBlack, darkThemeBg)
        assertNotEquals(nearBlack, adjusted)
        assertTrue(ContactPalette.contrastRatio(adjusted, darkThemeBg) >= 1.3)
        assertTrue(ContactPalette.relativeLuminance(adjusted) > ContactPalette.relativeLuminance(nearBlack))
    }

    @Test
    fun `already-legible presets pass through unchanged against both theme backgrounds`() {
        for (color in ContactPalette.colors) {
            assertEquals(
                "${color.name} vs dark bg",
                color.argb,
                ColorMath.adjustAccentForBackground(color.argb, darkThemeBg)
            )
            assertEquals(
                "${color.name} vs light bg",
                color.argb,
                ColorMath.adjustAccentForBackground(color.argb, lightThemeBg)
            )
        }
    }

    @Test
    fun `adjustAccentForBackground is deterministic`() {
        val nearWhite = 0xFFFEFEFE.toInt()
        val a = ColorMath.adjustAccentForBackground(nearWhite, lightThemeBg)
        val b = ColorMath.adjustAccentForBackground(nearWhite, lightThemeBg)
        assertEquals(a, b)
    }

    @Test
    fun `adjustAccentForBackground terminates when accent equals background exactly`() {
        val darkResult = ColorMath.adjustAccentForBackground(darkThemeBg, darkThemeBg)
        assertNotEquals(darkThemeBg, darkResult)
        assertTrue(ContactPalette.contrastRatio(darkResult, darkThemeBg) >= 1.3)

        val lightResult = ColorMath.adjustAccentForBackground(lightThemeBg, lightThemeBg)
        assertNotEquals(lightThemeBg, lightResult)
        assertTrue(ContactPalette.contrastRatio(lightResult, lightThemeBg) >= 1.3)
    }

    // ── adjustAccentForBackgroundStops (chat-background gradient guard) ───────────
    // The gradient separation floor is ColorMath's private MIN_GRADIENT_CONTRAST (= 2.0),
    // the value ThemePresetsTest pins the whole preset×background matrix to.
    private val gradientFloor = 2.0
    // Deep Plum's dark-theme stops (Sunset's paired background) — the owner's real case.
    private val deepPlumDarkStops = listOf(0xFF561C72.toInt(), 0xFF3D1452.toInt())

    private fun hueClose(a: Float, b: Float, tolerance: Float): Boolean {
        val d = abs(a - b) % 360f
        return minOf(d, 360f - d) <= tolerance
    }

    @Test
    fun `empty stops leave the accent unchanged`() {
        for (color in ContactPalette.colors) {
            assertEquals(color.argb, ColorMath.adjustAccentForBackgroundStops(color.argb, emptyList()))
        }
    }

    @Test
    fun `accent already clear of every stop passes through unchanged`() {
        val coral = 0xFFF2694B.toInt() // sunset contact — bright, well above the dark plum stops
        assertTrue(deepPlumDarkStops.all { ContactPalette.contrastRatio(coral, it) >= gradientFloor })
        assertEquals(coral, ColorMath.adjustAccentForBackgroundStops(coral, deepPlumDarkStops))
    }

    @Test
    fun `blending accent is nudged to clear the floor against both stops, hue preserved`() {
        // Sunset's violet sent bubble on Deep Plum's dark stops: near-identical hue, ~1.83
        // contrast against the brighter stop — the reported "sent bubble blends in" bug.
        val violet = 0xFF7C3AC9.toInt()
        assertTrue(
            "baseline should be below the floor for the nudge to matter",
            deepPlumDarkStops.any { ContactPalette.contrastRatio(violet, it) < gradientFloor }
        )

        val adjusted = ColorMath.adjustAccentForBackgroundStops(violet, deepPlumDarkStops)
        assertNotEquals(violet, adjusted)
        for (stop in deepPlumDarkStops) {
            assertTrue(
                "adjusted ${ColorMath.formatHexColor(adjusted)} vs ${ColorMath.formatHexColor(stop)} below floor",
                ContactPalette.contrastRatio(adjusted, stop) >= gradientFloor
            )
        }
        // Lightened away from the dark stops, with hue preserved (value-only nudge).
        assertTrue(ContactPalette.relativeLuminance(adjusted) > ContactPalette.relativeLuminance(violet))
        val (hueBefore, _, _) = ColorMath.argbToHsv(violet)
        val (hueAfter, _, _) = ColorMath.argbToHsv(adjusted)
        assertTrue("hue drifted $hueBefore -> $hueAfter", hueClose(hueBefore, hueAfter, 3f))
    }

    @Test
    fun `light stops darken a too-close accent to clear the floor`() {
        // A deep bubble against a pale gradient should be pushed DARKER, not lighter.
        val stops = listOf(0xFFF0E3F6.toInt(), 0xFFDFC5EC.toInt()) // deep plum light
        val muddy = 0xFFB08A8A.toInt() // a dusty rose that sits too close to the pale stops
        assertTrue(stops.any { ContactPalette.contrastRatio(muddy, it) < gradientFloor })
        val adjusted = ColorMath.adjustAccentForBackgroundStops(muddy, stops)
        assertTrue(ContactPalette.relativeLuminance(adjusted) < ContactPalette.relativeLuminance(muddy))
        for (stop in stops) {
            assertTrue(ContactPalette.contrastRatio(adjusted, stop) >= gradientFloor)
        }
    }
}
