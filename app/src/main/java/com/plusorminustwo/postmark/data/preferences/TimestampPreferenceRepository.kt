package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimestampPreferenceRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    private val _preference = MutableStateFlow(read())
    val preference: StateFlow<TimestampPreference> = _preference.asStateFlow()

    fun set(pref: TimestampPreference) {
        prefs.edit().putString(KEY, pref.name).apply()
        _preference.value = pref
    }

    private fun read(): TimestampPreference {
        val stored = prefs.getString(KEY, null) ?: return TimestampPreference.ALWAYS
        return runCatching { TimestampPreference.valueOf(stored) }.getOrDefault(TimestampPreference.ALWAYS)
    }

    companion object {
        private const val KEY = "timestamp_preference"
    }
}
