package com.plusorminustwo.postmark.ui.theme

/** Controls the app-wide font family. Persisted by [FontFamilyPreferenceRepository]. */
enum class FontFamilyPreference {
    SYSTEM,
    SERIF,
    MONOSPACE
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
