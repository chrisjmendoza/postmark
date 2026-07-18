package com.plusorminustwo.postmark.domain.customization

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Preset per-contact accent colors and the pure color math that derives sent-bubble
 * container/content colors from them (Phase B of user customization).
 *
 * Colors are packed ARGB Ints (0xAARRGGBB) — the same representation Compose's
 * `Color(Int)` constructor and [com.plusorminustwo.postmark.domain.model.Thread.accentColorArgb]
 * use. Deliberately free of Android/Compose imports so this stays plain-JVM testable;
 * callers wrap the result in `Color(...)` at the UI layer.
 */
object ContactPalette {

    data class PaletteColor(val name: String, val argb: Int)

    // Fraction blended toward black/white for the sent-bubble container, and the HSL
    // lightness multiplier used to darken the accent for on-container text in light
    // theme. Calibrated (see ContactPaletteTest) so every palette color × theme keeps
    // container/content contrast >= 3.0, anchored to the app's existing AccentBlue
    // (#378ADD) -> ContactBlueBg (#1A3A5C, dark) / #D6E8FA (light) relationship.
    private const val DARK_CONTAINER_BLEND = 0.6
    private const val LIGHT_CONTAINER_BLEND = 0.8
    private const val LIGHT_CONTENT_LIGHTNESS_SCALE = 0.62

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** 12 preset accent colors offered in the "Conversation color" picker. */
    val colors: List<PaletteColor> = listOf(
        PaletteColor("Red",          0xFFE2554A.toInt()),
        PaletteColor("Orange",       0xFFDB7B33.toInt()),
        PaletteColor("Amber",        0xFFD9A73B.toInt()),
        PaletteColor("Yellow-green", 0xFF9BB43C.toInt()),
        PaletteColor("Green",        0xFF4C9B54.toInt()),
        PaletteColor("Teal",         0xFF35997F.toInt()),
        PaletteColor("Cyan",         0xFF3D9BB0.toInt()),
        PaletteColor("Blue",         0xFF378ADD.toInt()), // matches the app's own accent blue
        PaletteColor("Indigo",       0xFF6E7BD1.toInt()),
        PaletteColor("Purple",       0xFF9B6BD1.toInt()),
        PaletteColor("Pink",         0xFFD1639A.toInt()),
        PaletteColor("Brown",        0xFFA47C5B.toInt())
    )

    /**
     * Sent-bubble background derived from [accentArgb]: blended toward black in dark
     * theme (dark, clearly-tinted — mirrors AccentBlue -> ContactBlueBg) and toward
     * white in light theme (mirrors AccentBlue -> #D6E8FA).
     */
    fun bubbleContainerColor(accentArgb: Int, isDark: Boolean): Int =
        if (isDark) blend(accentArgb, BLACK, DARK_CONTAINER_BLEND)
        else blend(accentArgb, WHITE, LIGHT_CONTAINER_BLEND)

    /**
     * Text/content color for that container: the raw accent in dark theme (mirrors
     * onPrimaryContainer == AccentBlue), a darkened accent in light theme (mirrors
     * onPrimaryContainer's #0056B3).
     */
    fun onBubbleContentColor(accentArgb: Int, isDark: Boolean): Int =
        if (isDark) accentArgb
        else scaleLightness(accentArgb, LIGHT_CONTENT_LIGHTNESS_SCALE)

    /** WCAG relative luminance (0..1) of an opaque ARGB color. */
    fun relativeLuminance(argb: Int): Double {
        val r = srgbToLinear(red(argb))
        val g = srgbToLinear(green(argb))
        val b = srgbToLinear(blue(argb))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio (>= 1.0) between two opaque ARGB colors. */
    fun contrastRatio(a: Int, b: Int): Double {
        val lighter = max(relativeLuminance(a), relativeLuminance(b))
        val darker = min(relativeLuminance(a), relativeLuminance(b))
        return (lighter + 0.05) / (darker + 0.05)
    }

    // ── Channel helpers ──────────────────────────────────────────────────────

    private fun red(argb: Int) = (argb ushr 16) and 0xFF
    private fun green(argb: Int) = (argb ushr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun blend(from: Int, to: Int, fraction: Double): Int {
        val f = fraction.coerceIn(0.0, 1.0)
        fun mix(a: Int, b: Int) = (a + (b - a) * f).roundToInt().coerceIn(0, 255)
        return rgb(mix(red(from), red(to)), mix(green(from), green(to)), mix(blue(from), blue(to)))
    }

    private fun srgbToLinear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    // ── HSL round trip (used only to darken content color in light theme — scaling
    // lightness in HSL space keeps hue/saturation intact, unlike a flat RGB scale). ──

    private fun scaleLightness(argb: Int, factor: Double): Int {
        val (h, s, l) = rgbToHsl(red(argb), green(argb), blue(argb))
        val (r, g, b) = hslToRgb(h, s, (l * factor).coerceIn(0.0, 1.0))
        return rgb(r, g, b)
    }

    private fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
        val rf = r / 255.0
        val gf = g / 255.0
        val bf = b / 255.0
        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val l = (maxC + minC) / 2.0
        if (maxC == minC) return Triple(0.0, 0.0, l)
        val d = maxC - minC
        val s = if (l > 0.5) d / (2.0 - maxC - minC) else d / (maxC + minC)
        val h = when (maxC) {
            rf -> ((gf - bf) / d + (if (gf < bf) 6.0 else 0.0))
            gf -> (bf - rf) / d + 2.0
            else -> (rf - gf) / d + 4.0
        } * 60.0
        return Triple(h, s, l)
    }

    private fun hslToRgb(h: Double, s: Double, l: Double): Triple<Int, Int, Int> {
        if (s == 0.0) {
            val v = (l * 255.0).roundToInt().coerceIn(0, 255)
            return Triple(v, v, v)
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val hk = h / 360.0
        fun hueToRgb(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1.0
            if (t > 1) t -= 1.0
            return when {
                t < 1.0 / 6.0 -> p + (q - p) * 6.0 * t
                t < 1.0 / 2.0 -> q
                t < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - t) * 6.0
                else -> p
            }
        }
        fun toByte(v: Double) = (v * 255.0).roundToInt().coerceIn(0, 255)
        return Triple(toByte(hueToRgb(hk + 1.0 / 3.0)), toByte(hueToRgb(hk)), toByte(hueToRgb(hk - 1.0 / 3.0)))
    }
}
