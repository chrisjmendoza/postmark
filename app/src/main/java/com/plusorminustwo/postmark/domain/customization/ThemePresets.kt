package com.plusorminustwo.postmark.domain.customization

/**
 * One curated theme preset: a named bundle of the three per-thread look fields
 * (sent-bubble color, contact color, chat background). **Applying a preset COPIES these
 * values** onto a thread — no reference is kept, so a thread never breaks if a preset is
 * renamed or removed, and every field stays independently editable afterward.
 *
 * Colors are opaque packed ARGB Ints (0xAARRGGBB), matching [ContactPalette] and
 * [com.plusorminustwo.postmark.domain.model.Thread]. [backgroundId] is a [ChatBackgrounds]
 * catalog id, or null for "no background" (renders as [ChatBackgrounds.None]). Deliberately
 * free of Android/Compose imports so this stays plain-JVM testable.
 */
data class ThemePreset(
    val id: String,
    val name: String,
    val sentArgb: Int,
    val contactArgb: Int,
    val backgroundId: String?
)

/**
 * The built-in catalog of 10 curated presets — the seed of the eventual shareable theme
 * "market" (see docs/theme-presets-plan.md). Each is built around a classic color-harmony
 * structure; sent colors skew deeper (they dominate the screen), contact colors skew
 * brighter (they also paint the avatar). Every color clears the repo's own WCAG content
 * floor and every sent/contact pair is mutually distinguishable — both proven in
 * ThemePresetsTest. Backgrounds are drawn only from [ChatBackgrounds], so
 * [ColorMath.adjustAccentForBackground]'s bubble-vs-background guard applies for free.
 */
object ThemePresets {

    /** All presets, in catalog display order. */
    val all: List<ThemePreset> = listOf(
        ThemePreset("ocean",     "Ocean",     0xFF2456C4.toInt(), 0xFF12A5A0.toInt(), "midnight_teal"),
        ThemePreset("sunset",    "Sunset",    0xFF7C3AC9.toInt(), 0xFFF2694B.toInt(), "deep_plum"),
        ThemePreset("forest",    "Forest",    0xFF2E7D46.toInt(), 0xFFD9A73B.toInt(), "deep_forest"),
        ThemePreset("classic",   "Classic",   0xFF378ADD.toInt(), 0xFFAEB9C4.toInt(), null),
        ThemePreset("ember",     "Ember",     0xFFC2451F.toInt(), 0xFFE8B04B.toInt(), "warm_charcoal"),
        ThemePreset("berry",     "Berry",     0xFFC42360.toInt(), 0xFF9B7EDE.toInt(), "dark_mauve"),
        ThemePreset("mint",      "Mint",      0xFF46627F.toInt(), 0xFF2FA97C.toInt(), null),
        ThemePreset("midnight",  "Midnight",  0xFF3D4DB7.toInt(), 0xFF58A8E8.toInt(), "deep_navy"),
        ThemePreset("bubblegum", "Bubblegum", 0xFFE0388A.toInt(), 0xFF3FA9E0.toInt(), null),
        ThemePreset("mono-pop",  "Mono Pop",  0xFF3E4A54.toInt(), 0xFFE23B3B.toInt(), null)
    )
}
