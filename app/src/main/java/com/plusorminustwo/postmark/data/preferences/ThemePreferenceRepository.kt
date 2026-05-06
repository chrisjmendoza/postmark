package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and exposes the user's theme preference (light / dark / system) via
 * a [StateFlow]. Backed by [SharedPreferences] so the value survives app restarts.
 *
 * Call [set] to update both the persisted value and the live [preference] flow.
 */
@Singleton
class ThemePreferenceRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    private val _preference = MutableStateFlow(read())
    val preference: StateFlow<ThemePreference> = _preference.asStateFlow()

    /** Updates the stored theme preference and emits the new value on [preference]. */
    fun set(pref: ThemePreference) {
        prefs.edit().putString(KEY, pref.name).apply()
        _preference.value = pref
    }

    private fun read(): ThemePreference {
        val stored = prefs.getString(KEY, null) ?: return ThemePreference.SYSTEM
        return runCatching { ThemePreference.valueOf(stored) }.getOrDefault(ThemePreference.SYSTEM)
    }

    companion object {
        private const val KEY = "theme_preference"
    }
}
