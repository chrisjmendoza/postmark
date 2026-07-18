package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import com.plusorminustwo.postmark.ui.theme.BubbleStylePreference
import com.plusorminustwo.postmark.ui.theme.bubbleStylePreferenceFromString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and exposes the user's bubble style preference (rounded / pill / square)
 * via a [StateFlow]. Backed by [SharedPreferences] so the value survives app restarts.
 *
 * Call [set] to update both the persisted value and the live [preference] flow.
 */
@Singleton
class BubbleStylePreferenceRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    private val _preference = MutableStateFlow(read())
    val preference: StateFlow<BubbleStylePreference> = _preference.asStateFlow()

    /** Updates the stored bubble style preference and emits the new value on [preference]. */
    fun set(pref: BubbleStylePreference) {
        prefs.edit().putString(KEY, pref.name).apply()
        _preference.value = pref
    }

    private fun read(): BubbleStylePreference = bubbleStylePreferenceFromString(prefs.getString(KEY, null))

    companion object {
        private const val KEY = "bubble_style_preference"
    }
}
