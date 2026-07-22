package com.plusorminustwo.postmark.ui.theme

/** Controls the shape of message bubbles app-wide. Persisted by [BubbleStylePreferenceRepository]. */
enum class BubbleStylePreference {
    ROUNDED,
    PILL,
    SQUARE
}

/**
 * Parses a stored preference name, falling back to [BubbleStylePreference.ROUNDED] for a
 * null or unrecognized value. Extracted as a pure function (rather than inlined into the
 * repository's read()) so it's testable without the Compose classpath.
 */
fun bubbleStylePreferenceFromString(name: String?): BubbleStylePreference {
    val stored = name ?: return BubbleStylePreference.ROUNDED
    return runCatching { BubbleStylePreference.valueOf(stored) }.getOrDefault(BubbleStylePreference.ROUNDED)
}
