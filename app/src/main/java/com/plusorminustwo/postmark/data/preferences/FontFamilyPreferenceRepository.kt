package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import com.plusorminustwo.postmark.ui.theme.FontFamilyPreference
import com.plusorminustwo.postmark.ui.theme.fontFamilyPreferenceFromString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and exposes the user's font family preference (system / serif / monospace)
 * via a [StateFlow]. Backed by [SharedPreferences] so the value survives app restarts.
 *
 * Call [set] to update both the persisted value and the live [preference] flow.
 */
@Singleton
class FontFamilyPreferenceRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    private val _preference = MutableStateFlow(read())
    val preference: StateFlow<FontFamilyPreference> = _preference.asStateFlow()

    /** Updates the stored font family preference and emits the new value on [preference]. */
    fun set(pref: FontFamilyPreference) {
        prefs.edit().putString(KEY, pref.name).apply()
        _preference.value = pref
    }

    private fun read(): FontFamilyPreference = fontFamilyPreferenceFromString(prefs.getString(KEY, null))

    companion object {
        private const val KEY = "font_family_preference"
    }
}
