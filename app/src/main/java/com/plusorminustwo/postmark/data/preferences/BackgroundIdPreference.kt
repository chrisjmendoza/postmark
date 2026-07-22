package com.plusorminustwo.postmark.data.preferences

import kotlinx.coroutines.flow.StateFlow

/**
 * A single stored background id — a
 * [com.plusorminustwo.postmark.domain.customization.ChatBackgrounds] catalog id or an
 * `image:<fileName>` id — with null meaning "unset" (no background).
 *
 * Implemented by [ChatBackgroundPreferenceRepository] (the global chat default) and
 * [HomeBackgroundPreferenceRepository] (the conversation list). They stay separate
 * singletons over separate keys — the abstraction exists only so a caller holding a
 * "which surface?" choice can select one and then treat them identically, instead of
 * branching at every read and write.
 */
interface BackgroundIdPreference {
    /** The stored id; null = unset. */
    val backgroundId: StateFlow<String?>

    /** Updates the stored id and emits it on [backgroundId]; pass null to clear. */
    fun set(id: String?)
}
