package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and exposes the user's global default chat-background id via a
 * [StateFlow]. Backed by [SharedPreferences] so the value survives app restarts.
 * Null means no background is set (falls back to none). A per-thread override,
 * if set, always takes precedence over this global default.
 *
 * Call [set] to update both the persisted value and the live [backgroundId] flow.
 */
@Singleton
class ChatBackgroundPreferenceRepository @Inject constructor(
    @ApplicationContext context: Context
) : BackgroundIdPreference {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    private val _backgroundId = MutableStateFlow(read())
    override val backgroundId: StateFlow<String?> = _backgroundId.asStateFlow()

    /** Updates the stored global default background id and emits the new value
     *  on [backgroundId]; pass null to clear it. */
    override fun set(id: String?) {
        if (id == null) {
            prefs.edit().remove(KEY).apply()
        } else {
            prefs.edit().putString(KEY, id).apply()
        }
        _backgroundId.value = id
    }

    private fun read(): String? = prefs.getString(KEY, null)

    companion object {
        private const val KEY = "global_chat_background"
    }
}
