package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the global Material You (wallpaper-derived dynamic color) toggle.
 *
 * When enabled on API 31+, [com.plusorminustwo.postmark.ui.theme.PostmarkTheme] uses
 * `dynamicDarkColorScheme`/`dynamicLightColorScheme` instead of Postmark's brand color
 * schemes. Below API 31 the preference is stored but never applied — the Appearance
 * screen hides the toggle row entirely on those devices.
 */
@Singleton
class DynamicColorPreferenceRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
        _enabled.value = enabled
    }

    companion object {
        private const val KEY = "dynamic_color_enabled"
    }
}
