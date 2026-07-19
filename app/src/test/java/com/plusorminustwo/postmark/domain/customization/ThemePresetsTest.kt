package com.plusorminustwo.postmark.domain.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Proves the design invariants of the built-in [ThemePresets] catalog: legible bubble
 * content, distinguishable sent/contact pairs, valid backgrounds, and well-formed ids.
 * These are the floors the design doc promises; if a future edit to a color breaks one,
 * this test fails rather than shipping an illegible or muddy preset.
 */
class ThemePresetsTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val kebabCase = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    /** Highest contrast [color] can reach against pure white or pure black — i.e. the
     *  contrast of the white/black content color [ContactPalette] would actually pick. */
    private fun bestContentContrast(color: Int): Double =
        max(ContactPalette.contrastRatio(color, white), ContactPalette.contrastRatio(color, black))

    /** Smallest angular distance between two hues, wrapping at 360°. */
    private fun hueDelta(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return min(d, 360f - d)
    }

    // ── Shape ──────────────────────────────────────────────────────────────────────

    @Test
    fun `catalog has exactly 10 presets`() {
        assertEquals(10, ThemePresets.all.size)
    }

    @Test
    fun `every preset color is fully opaque`() {
        for (p in ThemePresets.all) {
            assertEquals("${p.name} sent alpha", 0xFF, (p.sentArgb ushr 24) and 0xFF)
            assertEquals("${p.name} contact alpha", 0xFF, (p.contactArgb ushr 24) and 0xFF)
        }
    }

    // ── (a) Content-contrast floor ───────────────────────────────────────────────
    // Both bubble colors must clear 4.5:1 against the better of white/black — the same
    // floor ContactPaletteTest proves for the 12 pickers. (Mathematically this holds for
    // any opaque color: the white/black best-of contrast bottoms out at ~4.58 near
    // luminance 0.18, so no preset color ever needed a lightness nudge.)

    @Test
    fun `both bubble colors clear the 4point5 content-contrast floor for every preset`() {
        for (p in ThemePresets.all) {
            assertTrue(
                "${p.name}: sent ${hex(p.sentArgb)} content contrast below 4.5",
                bestContentContrast(p.sentArgb) >= 4.5
            )
            assertTrue(
                "${p.name}: contact ${hex(p.contactArgb)} content contrast below 4.5",
                bestContentContrast(p.contactArgb) >= 4.5
            )
        }
    }

    // ── (b) Sent-vs-contact separation ───────────────────────────────────────────
    // The two bubbles in a thread must read as different: either enough luminance
    // contrast (>= 1.4), or — for two vivid hues — a hue that's clearly apart (both
    // saturations >= 0.25 AND >= 40° between them).

    @Test
    fun `sent and contact are mutually distinguishable for every preset`() {
        for (p in ThemePresets.all) {
            val luminanceContrast = ContactPalette.contrastRatio(p.sentArgb, p.contactArgb)
            val (sentHue, sentSat, _) = ColorMath.argbToHsv(p.sentArgb)
            val (contactHue, contactSat, _) = ColorMath.argbToHsv(p.contactArgb)
            val hueSeparated =
                sentSat >= 0.25f && contactSat >= 0.25f && hueDelta(sentHue, contactHue) >= 40f
            assertTrue(
                "${p.name}: sent ${hex(p.sentArgb)} and contact ${hex(p.contactArgb)} are not " +
                    "distinguishable (luminance contrast $luminanceContrast, hue-separated $hueSeparated)",
                luminanceContrast >= 1.4 || hueSeparated
            )
        }
    }

    // ── (c) Background validity ──────────────────────────────────────────────────

    @Test
    fun `every non-null background id resolves to a real catalog entry`() {
        for (p in ThemePresets.all) {
            val backgroundId = p.backgroundId ?: continue
            val resolved = ChatBackgrounds.resolve(backgroundId)
            assertNotEquals(
                "${p.name}: background '$backgroundId' does not resolve to a catalog entry",
                ChatBackgrounds.None,
                resolved
            )
            assertEquals(
                "${p.name}: background id round-trips through the catalog",
                backgroundId,
                resolved.id
            )
        }
    }

    // ── (d) Identity hygiene ─────────────────────────────────────────────────────

    @Test
    fun `ids are unique`() {
        val ids = ThemePresets.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `names are unique`() {
        val names = ThemePresets.all.map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `ids are kebab-case`() {
        for (p in ThemePresets.all) {
            assertTrue("${p.name}: id '${p.id}' is not kebab-case", kebabCase.matches(p.id))
        }
    }

    // ── (e) Bubble-vs-background separation (Phase TP) ────────────────────────────
    // Every preset that carries a chat background: both its sent and contact containers,
    // once guarded through ColorMath against that gradient's stops for a theme variant,
    // clear the gradient separation floor against BOTH stops of that variant — dark stops
    // in dark theme, light stops in light theme. This is the pin that stops a sent bubble
    // from blending into its own chat background (the reported Sunset-on-Deep-Plum bug).
    // The floor mirrors ColorMath's private MIN_GRADIENT_CONTRAST (= 2.0).

    private val gradientFloor = 2.0

    @Test
    fun `every backgrounded preset clears the gradient floor against both stops in both variants`() {
        var backgroundedCount = 0
        for (p in ThemePresets.all) {
            val bg = ChatBackgrounds.resolve(p.backgroundId ?: continue)
            backgroundedCount++
            for ((label, container) in listOf("sent" to p.sentArgb, "contact" to p.contactArgb)) {
                for ((variant, stops) in listOf("dark" to bg.darkColorsArgb, "light" to bg.lightColorsArgb)) {
                    val adjusted = ColorMath.adjustAccentForBackgroundStops(container, stops)
                    for (stop in stops) {
                        val contrast = ContactPalette.contrastRatio(adjusted, stop)
                        assertTrue(
                            "${p.name} $label on ${bg.id} $variant: ${hex(container)} -> ${hex(adjusted)} " +
                                "contrast ${"%.3f".format(contrast)} vs stop ${hex(stop)} below $gradientFloor",
                            contrast >= gradientFloor
                        )
                    }
                    // Value-only nudge: the guard never changes hue.
                    assertTrue(
                        "${p.name} $label on ${bg.id} $variant: hue drifted",
                        hueDelta(
                            ColorMath.argbToHsv(container).first,
                            ColorMath.argbToHsv(adjusted).first
                        ) <= 3f
                    )
                }
            }
        }
        // Sanity: the matrix actually covered the backgrounded presets, not an empty loop.
        assertEquals(ThemePresets.all.count { it.backgroundId != null }, backgroundedCount)
        assertTrue("expected at least one backgrounded preset", backgroundedCount > 0)
    }

    private fun hex(argb: Int): String = ColorMath.formatHexColor(argb)
}
