package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and exposes the user's global app accent color (Phase I of user
 * customization) via a [StateFlow]. Backed by [SharedPreferences] so the value
 * survives app restarts. Null means no override is set — [PostmarkTheme][
 * com.plusorminustwo.postmark.ui.theme.PostmarkTheme] falls back to Postmark's own
 * brand blue. Ignored entirely when Material You (dynamic color) is enabled — that
 * gate lives in `PostmarkTheme`, not here.
 *
 * Same nullable-value pattern as [ChatBackgroundPreferenceRepository]: an absent key
 * means null, and [set] removes the key entirely for null rather than writing a
 * sentinel value.
 *
 * Call [set] to update both the persisted value and the live [accentArgb] flow.
 */
@Singleton
class AppAccentPreferenceRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    private val _accentArgb = MutableStateFlow(read())
    val accentArgb: StateFlow<Int?> = _accentArgb.asStateFlow()

    /** Updates the stored global app accent (packed ARGB), and emits the new value
     *  on [accentArgb]; pass null to clear it back to the default brand blue. */
    fun set(argb: Int?) {
        if (argb == null) {
            prefs.edit().remove(KEY).apply()
        } else {
            prefs.edit().putInt(KEY, argb).apply()
        }
        _accentArgb.value = argb
    }

    private fun read(): Int? = if (prefs.contains(KEY)) prefs.getInt(KEY, 0) else null

    companion object {
        private const val KEY = "app_accent_argb"
    }
}
