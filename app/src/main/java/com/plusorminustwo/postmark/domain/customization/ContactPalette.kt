package com.plusorminustwo.postmark.domain.customization

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** 12 preset accent colors offered in the "Their color" / "Your bubble color" pickers. */
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
     * Text/icon color for that container: pure white or pure black, whichever has the
     * higher WCAG contrast ratio against [accentArgb]. This is provably >= ~4.5 for any
     * opaque color (the floor is proven empirically for all 12 presets in
     * ContactPaletteTest) since one of white/black is always the better choice as the
     * accent's luminance moves away from either extreme. [isDark] is accepted for
     * call-site symmetry but doesn't change the result — the choice only depends on the
     * accent itself.
     */
    fun onBubbleContentColor(accentArgb: Int, isDark: Boolean): Int =
        if (contrastRatio(accentArgb, WHITE) >= contrastRatio(accentArgb, BLACK)) WHITE else BLACK

    // ── Accent derivation ────────────────────────────────────────────────────

    /** A bubble direction's container fill + its legible content color (packed ARGB). */
    data class AccentColorPair(val containerArgb: Int, val contentArgb: Int)

    /**
     * Derives one accented bubble's container/content pair from a raw [accentArgb]: the
     * container is the accent guarded away from the background it will sit on, and the content
     * is white/black-by-contrast against that (possibly-nudged) container. The single owner of
     * "what an accented bubble looks like", shared by [resolveThreadBubbleColors] and
     * PostmarkTheme's global app accent.
     *
     * When [backgroundStopsArgb] is non-empty (a built-in chat-background GRADIENT's stops for
     * the current theme variant) the container is guarded against those stops via
     * [ColorMath.adjustAccentForBackgroundStops]; otherwise it's guarded against the single
     * [backgroundArgb] (the plain theme background, or a custom-image background) via
     * [ColorMath.adjustAccentForBackground] — the identity for every preset against the plain
     * theme backgrounds (proven in ColorMathTest), so un-customized/preset colors on an
     * un-backgrounded thread are unchanged.
     */
    fun deriveAccentPair(
        accentArgb: Int,
        backgroundArgb: Int,
        isDark: Boolean,
        backgroundStopsArgb: List<Int> = emptyList()
    ): AccentColorPair {
        val container =
            if (backgroundStopsArgb.isNotEmpty())
                ColorMath.adjustAccentForBackgroundStops(accentArgb, backgroundStopsArgb)
            else
                ColorMath.adjustAccentForBackground(accentArgb, backgroundArgb)
        return AccentColorPair(container, onBubbleContentColor(container, isDark))
    }

    /**
     * A thread's four nullable bubble colors (packed ARGB), matching
     * `BubbleAccentColors`'s semantics: a null field leaves that direction on
     * MessageBubble's own theme default.
     */
    data class ThreadBubbleColors(
        val receivedContainerArgb: Int? = null,
        val receivedContentArgb: Int? = null,
        val sentContainerArgb: Int? = null,
        val sentContentArgb: Int? = null
    )

    /**
     * Resolves a thread's received + sent bubble colors from its per-thread overrides and
     * the global app accent. The received pair comes from [accentColorArgb], the sent pair
     * from [sentColorArgb] (each null when unset). When the thread sets no sent color but a
     * global [appAccentArgb] is active, PostmarkTheme has already painted primaryContainer
     * the vivid accent, so the sent CONTENT falls back to [appAccentOnPrimaryContainerArgb]
     * (its matching white/black-by-contrast color) to stay legible — the sole exception to
     * "null = theme default".
     *
     * [backgroundStopsArgb] carries the active chat background the bubbles will sit on: a
     * built-in gradient's stops for the current theme variant guard the containers away from
     * the gradient (so a sent bubble can't blend into it); empty (no background, or a custom
     * image) falls back to guarding the single [backgroundArgb]. Both directions use it.
     */
    fun resolveThreadBubbleColors(
        accentColorArgb: Int?,
        sentColorArgb: Int?,
        appAccentArgb: Int?,
        appAccentOnPrimaryContainerArgb: Int,
        isDark: Boolean,
        backgroundArgb: Int,
        backgroundStopsArgb: List<Int> = emptyList()
    ): ThreadBubbleColors {
        val received = accentColorArgb?.let { deriveAccentPair(it, backgroundArgb, isDark, backgroundStopsArgb) }
        val sent = sentColorArgb?.let { deriveAccentPair(it, backgroundArgb, isDark, backgroundStopsArgb) }
        val sentContentFallback =
            if (appAccentArgb != null && sentColorArgb == null) appAccentOnPrimaryContainerArgb else null
        return ThreadBubbleColors(
            receivedContainerArgb = received?.containerArgb,
            receivedContentArgb = received?.contentArgb,
            sentContainerArgb = sent?.containerArgb,
            sentContentArgb = sent?.contentArgb ?: sentContentFallback
        )
    }

    // ── Bubble depth gradient (Phase FB2) ────────────────────────────────────────

    /** Nominal HSV-value nudge for each bubble-gradient stop: the top is +[this], the
     *  bottom −[this]. Kept small so a bubble reads as gently lit, not striped. */
    const val BUBBLE_GRADIENT_V_DELTA = 0.06f
    private const val BUBBLE_GRADIENT_V_STEP = 0.01f
    private const val CONTENT_CONTRAST_FLOOR = 4.5

    /**
     * Two vertical-gradient stops — top then bottom — that give a solid bubble
     * [containerArgb] a subtle top-lit look without changing its hue or breaking its
     * legibility. The top raises the container's HSV *value* by [BUBBLE_GRADIENT_V_DELTA],
     * the bottom lowers it by the same, so the fill reads as gently lit rather than flat.
     * Callers wrap each stop in `Color(...)` and build a `Brush.verticalGradient`.
     *
     * Each stop is guarded so the container's own content color ([onBubbleContentColor],
     * white/black-by-contrast) still clears the [CONTENT_CONTRAST_FLOOR] WCAG floor against
     * it: a full-delta stop that would drop below the floor is walked back toward the base
     * container in [BUBBLE_GRADIENT_V_STEP] steps until it clears. The base container itself
     * always clears it (white/black-by-contrast is >= 4.58 for any opaque color), so this
     * always terminates. Only a few borderline mid-tone presets ever hit the guard, and only
     * on one of the two stops, so the gradient stays perceptible everywhere. Hue and
     * saturation are always preserved. Same packed-ARGB convention as the rest of this object.
     */
    fun bubbleGradientStops(containerArgb: Int): List<Int> {
        val (h, s, v) = ColorMath.argbToHsv(containerArgb)
        val content = onBubbleContentColor(containerArgb, isDark = true)
        return listOf(
            legibleGradientStop(h, s, v, BUBBLE_GRADIENT_V_DELTA, content),
            legibleGradientStop(h, s, v, -BUBBLE_GRADIENT_V_DELTA, content)
        )
    }

    /** One gradient stop at HSV ([h],[s],[v]+[delta]), its value-nudge walked back toward 0
     *  in [BUBBLE_GRADIENT_V_STEP] steps until [contentArgb] clears [CONTENT_CONTRAST_FLOOR]
     *  against it (the base container, delta 0, always clears — so this terminates). */
    private fun legibleGradientStop(h: Float, s: Float, v: Float, delta: Float, contentArgb: Int): Int {
        val sign = if (delta >= 0f) 1f else -1f
        var magnitude = abs(delta)
        while (magnitude > 0f) {
            val stop = ColorMath.hsvToArgb(h, s, (v + sign * magnitude).coerceIn(0f, 1f))
            if (contrastRatio(stop, contentArgb) >= CONTENT_CONTRAST_FLOOR) return stop
            magnitude -= BUBBLE_GRADIENT_V_STEP
        }
        return ColorMath.hsvToArgb(h, s, v)
    }

    // ── Cross-tinted bubble text ─────────────────────────────────────────────────

    /** WCAG AA floor for normal-size body text. Unlike the deliberately-low
     *  [ColorMath.adjustAccentForBackground] guards (which only stop a bubble from vanishing
     *  into the screen), cross-tinted text has to be genuinely *readable*, so this is the real
     *  4.5 bar. */
    private const val CROSS_TINT_CONTRAST_FLOOR = 4.5

    private const val CROSS_TINT_V_STEP = 0.04f
    /** 1.0 / [CROSS_TINT_V_STEP] — enough steps to walk the full HSV value range from any start. */
    private const val CROSS_TINT_MAX_STEPS = 25

    /**
     * Body-text color for a bubble filled with [containerArgb] when the thread's OTHER
     * direction is customized to [otherAccentArgb] — the "flip" that paints a green sent
     * bubble's text in the received bubble's orange, and vice versa.
     *
     * A raw flip is unreadable: the presets are all mid-tones of similar luminance, so e.g.
     * Orange on Green is 1.13:1 — invisible. So the other accent's HUE AND SATURATION are kept
     * (that's what makes it read as "the other color") while its HSV *value* is walked in
     * [CROSS_TINT_V_STEP] steps until it clears [CROSS_TINT_CONTRAST_FLOOR] against the
     * container. Orange-on-green lands on a deep burnt orange; the color is still obviously
     * orange, and it's legible.
     *
     * BOTH directions are walked and the one needing the smaller change wins. A single
     * direction chosen from the container's luminance (what the [ColorMath] guards do) is not
     * enough here: against mid-tone Green, lightening Orange tops out at 1.51:1 even at full
     * value — only darkening ever reaches 4.5 — so a one-way walk would strand on the fallback
     * for the most common preset pairs.
     *
     * If neither direction clears (a saturated hue with no luminance headroom on either side
     * of the container) this falls back to [onBubbleContentColor] — white/black-by-contrast,
     * which always clears — so the text is never illegible, it just doesn't get the flip.
     * Returns [otherAccentArgb] untouched when it already clears the floor.
     */
    fun crossTintedTextColor(otherAccentArgb: Int, containerArgb: Int): Int {
        if (contrastRatio(otherAccentArgb, containerArgb) >= CROSS_TINT_CONTRAST_FLOOR) {
            return otherAccentArgb
        }
        val (h, s, v) = ColorMath.argbToHsv(otherAccentArgb)
        val darker = walkValueToTextFloor(h, s, v, -CROSS_TINT_V_STEP, containerArgb)
        val lighter = walkValueToTextFloor(h, s, v, CROSS_TINT_V_STEP, containerArgb)
        val best = listOfNotNull(darker, lighter).minByOrNull { it.second }
        return best?.first ?: onBubbleContentColor(containerArgb, isDark = true)
    }

    /**
     * Walks HSV value from [v] by [step] (clamped to 0..1) until the resulting color clears
     * [CROSS_TINT_CONTRAST_FLOOR] against [containerArgb], preserving [h] and [s]. Returns the
     * cleared color paired with the number of steps it took (so the caller can prefer the
     * smaller change), or null if [CROSS_TINT_MAX_STEPS] — the full value range — is exhausted
     * without clearing.
     */
    private fun walkValueToTextFloor(
        h: Float,
        s: Float,
        v: Float,
        step: Float,
        containerArgb: Int
    ): Pair<Int, Int>? {
        var value = v
        for (stepsTaken in 1..CROSS_TINT_MAX_STEPS) {
            value = (value + step).coerceIn(0f, 1f)
            val candidate = ColorMath.hsvToArgb(h, s, value)
            if (contrastRatio(candidate, containerArgb) >= CROSS_TINT_CONTRAST_FLOOR) {
                return candidate to stepsTaken
            }
        }
        return null
    }

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

    private fun srgbToLinear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
