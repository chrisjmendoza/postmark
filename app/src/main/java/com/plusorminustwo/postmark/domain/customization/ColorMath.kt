package com.plusorminustwo.postmark.domain.customization

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure HSV<->ARGB conversion, hex parse/format, and a legibility guard for
 * user-picked custom colors (Phase H of user customization). Same packed-ARGB
 * convention as [ContactPalette] (0xAARRGGBB Ints) — callers wrap the result in
 * `Color(...)` at the UI layer. Deliberately free of Android/Compose imports so
 * this stays plain-JVM testable. v1's picker never produces translucent colors,
 * so every function here treats alpha as always FF (opaque).
 */
object ColorMath {

    private const val OPAQUE = 0xFF shl 24
    private val HEX_DIGITS = (('0'..'9') + ('a'..'f') + ('A'..'F')).toSet()

    // ── HSV <-> ARGB ─────────────────────────────────────────────────────────────

    /**
     * Converts HSV ([h] degrees, [s]/[v] 0..1) to an opaque packed ARGB Int.
     * [h] wraps modulo 360 (e.g. -10 == 350); [s]/[v] are coerced into 0..1.
     */
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val hue = ((h % 360f) + 360f) % 360f
        val sat = s.coerceIn(0f, 1f)
        val value = v.coerceIn(0f, 1f)

        val c = value * sat
        val x = c * (1f - abs((hue / 60f) % 2f - 1f))
        val m = value - c
        val (r1, g1, b1) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = (((r1 + m) * 255f).roundToInt()).coerceIn(0, 255)
        val g = (((g1 + m) * 255f).roundToInt()).coerceIn(0, 255)
        val b = (((b1 + m) * 255f).roundToInt()).coerceIn(0, 255)
        return OPAQUE or (r shl 16) or (g shl 8) or b
    }

    /**
     * Converts an opaque packed ARGB Int to (hue 0..360, saturation 0..1, value
     * 0..1). Alpha is ignored. Grey/black/white inputs (saturation 0) report hue 0
     * — hue is meaningless there, matching [hsvToArgb]'s convention of ignoring hue
     * when saturation is 0.
     */
    fun argbToHsv(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * ((((g - b) / delta) % 6f + 6f) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val saturation = if (max == 0f) 0f else delta / max
        return Triple(hue, saturation, max)
    }

    // ── Hex ─────────────────────────────────────────────────────────────────────

    /**
     * Parses "#RRGGBB" or "RRGGBB" (case-insensitive, optional leading '#',
     * surrounding whitespace tolerated) into an opaque packed ARGB Int. Anything
     * else — wrong length, non-hex characters, 3-digit shorthand, 8-digit
     * "#AARRGGBB" (not supported in v1 — alpha is always FF), blank/empty — returns
     * null. Never throws.
     */
    fun parseHexColor(input: String): Int? {
        val digits = input.trim().removePrefix("#")
        if (digits.length != 6 || digits.any { it !in HEX_DIGITS }) return null
        val rgb = digits.toIntOrNull(16) ?: return null
        return OPAQUE or rgb
    }

    /** Formats an ARGB Int as uppercase "#RRGGBB"; alpha is ignored. */
    fun formatHexColor(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    // ── Legibility guard against the chat background ────────────────────────────

    /** Anti-vanish floor for a single, EXTREME background — the near-black/near-white
     *  theme background, or a custom image whose content is unknowable. Deliberately low:
     *  it only stops a custom pick from disappearing into the screen (e.g. a near-white
     *  pick in light theme), not to guarantee text-level legibility. Every [ContactPalette]
     *  preset already clears it, so presets pass through byte-identical. */
    private const val MIN_BACKGROUND_CONTRAST = 1.3

    /** Separation floor for a built-in chat-background GRADIENT (Phase TP). Its stops are
     *  saturated mid-tones that can share the bubble's own hue (e.g. a violet sent bubble on
     *  the Deep Plum gradient), so a much stronger floor than [MIN_BACKGROUND_CONTRAST] is
     *  needed for the bubble to read as a distinct object rather than blend in. 2.0 is the
     *  highest round floor every [ThemePresets] bubble can actually REACH against both stops
     *  of its paired gradient in both theme variants while only nudging HSV value and
     *  preserving hue (pinned in ColorMathTest); the tightest case — Sunset's violet sent
     *  bubble on Deep Plum's brighter dark stop — tops out at ~2.69 at full value, so 2.0
     *  leaves headroom. */
    private const val MIN_GRADIENT_CONTRAST = 2.0

    private const val VALUE_STEP = 0.04f
    private const val MAX_STEPS = 30

    /**
     * Single-background entry point: nudges [accentArgb]'s HSV value away from
     * [backgroundArgb]'s luminance until their WCAG contrast clears [MIN_BACKGROUND_CONTRAST].
     * Used for the plain theme background and for custom-IMAGE chat backgrounds (image pixels
     * are unknowable, so the theme background is the best available proxy). Accents that
     * already clear the bar are returned completely unchanged (no HSV round-trip) — this is
     * what keeps every [ContactPalette] preset byte-identical against both theme backgrounds.
     * See [adjustAccentToClearStops] for the walk itself.
     */
    fun adjustAccentForBackground(accentArgb: Int, backgroundArgb: Int): Int =
        adjustAccentToClearStops(accentArgb, listOf(backgroundArgb), MIN_BACKGROUND_CONTRAST)

    /**
     * Built-in chat-background-gradient counterpart of [adjustAccentForBackground]: nudges
     * [accentArgb] away until it clears the stronger [MIN_GRADIENT_CONTRAST] against EVERY
     * stop in [backgroundStopsArgb] — a gradient's stops for the CURRENT theme variant (dark
     * stops in dark theme). Empty stops ("no background") return [accentArgb] unchanged. The
     * curated gradients keep their two stops close together and on one side of mid-grey, so a
     * single value walk (direction chosen from the stops' average luminance) clears both at
     * once while preserving hue. This is the guard that stops a sent bubble from blending into
     * its chat-background gradient.
     */
    fun adjustAccentForBackgroundStops(accentArgb: Int, backgroundStopsArgb: List<Int>): Int =
        if (backgroundStopsArgb.isEmpty()) accentArgb
        else adjustAccentToClearStops(accentArgb, backgroundStopsArgb, MIN_GRADIENT_CONTRAST)

    /**
     * Walks [accentArgb]'s HSV *value* (lightness) in bounded [VALUE_STEP] increments — away
     * from [stops]' average luminance (darkening when they're the lighter side, lightening
     * when they're the darker side) — until its WCAG contrast clears [floor] against EVERY
     * stop, preserving hue and saturation. Accents that already clear the floor against all
     * stops are returned completely unchanged (no HSV round-trip). [MAX_STEPS] bounds the walk
     * so it always terminates; if the value extreme is reached without clearing (no headroom
     * left in the accent's hue/saturation) the last candidate is returned rather than looping.
     */
    private fun adjustAccentToClearStops(accentArgb: Int, stops: List<Int>, floor: Double): Int {
        if (stops.all { ContactPalette.contrastRatio(accentArgb, it) >= floor }) return accentArgb
        val darken = stops.map { ContactPalette.relativeLuminance(it) }.average() >= 0.5
        val (hue, sat, initialValue) = argbToHsv(accentArgb)
        var value = initialValue
        var candidate = accentArgb
        for (step in 1..MAX_STEPS) {
            value = if (darken) (value - VALUE_STEP).coerceAtLeast(0f)
            else (value + VALUE_STEP).coerceAtMost(1f)
            candidate = hsvToArgb(hue, sat, value)
            if (stops.all { ContactPalette.contrastRatio(candidate, it) >= floor }) break
        }
        return candidate
    }
}
