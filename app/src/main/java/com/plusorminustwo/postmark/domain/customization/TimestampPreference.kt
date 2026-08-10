package com.plusorminustwo.postmark.domain.customization

/** Controls when message timestamps are shown in the thread view.
 *  Persisted by [TimestampPreferenceRepository]. */
enum class TimestampPreference {
    ALWAYS,
    ON_TAP,
    NEVER
}
