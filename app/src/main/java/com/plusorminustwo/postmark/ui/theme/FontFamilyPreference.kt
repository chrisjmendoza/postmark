package com.plusorminustwo.postmark.ui.theme

/**
 * Controls the app-wide font family. Persisted by [FontFamilyPreferenceRepository] BY NAME,
 * so entries may be added freely but must never be renamed or removed — a stored name that
 * no longer resolves silently falls back to [SYSTEM] (see [fontFamilyPreferenceFromString]).
 *
 * [SYSTEM]/[SERIF]/[MONOSPACE] map to the platform's own generic families and cost nothing.
 * Every other entry is a bundled OFL typeface under `res/font` (license texts in
 * `docs/font-licenses/`); see [toFontFamilyOrNull] for the resource mapping.
 *
 * Stays free of Compose/Android imports so it (and [fontFamilyPreferenceFromString]) remain
 * plain-JVM testable — the Compose [androidx.compose.ui.text.font.FontFamily] mapping lives
 * in Theme.kt instead.
 *
 * @param displayName Shown as the option's label — for a bundled face this is the typeface's
 *                    real name, which the picker also renders IN that typeface.
 * @param description One-line character note shown under the label in the picker.
 */
enum class FontFamilyPreference(val displayName: String, val description: String) {
    SYSTEM("System default", "Your device's own font"),
    SERIF("Serif", "The system's traditional serif"),
    MONOSPACE("Monospace", "The system's fixed-width font"),
    INTER("Inter", "Neutral, drawn for screens"),
    POPPINS("Poppins", "Geometric, with round bowls"),
    NUNITO("Nunito", "Soft and friendly"),
    LORA("Lora", "A serif with brushed contrast"),
    PLAYFAIR_DISPLAY("Playfair Display", "High-contrast and editorial"),
    JETBRAINS_MONO("JetBrains Mono", "A roomier fixed-width")
}

/**
 * Parses a stored preference name, falling back to [FontFamilyPreference.SYSTEM] for a
 * null or unrecognized value. Extracted as a pure function (rather than inlined into the
 * repository's read()) so it's testable without the Compose classpath.
 */
fun fontFamilyPreferenceFromString(name: String?): FontFamilyPreference {
    val stored = name ?: return FontFamilyPreference.SYSTEM
    return runCatching { FontFamilyPreference.valueOf(stored) }.getOrDefault(FontFamilyPreference.SYSTEM)
}
