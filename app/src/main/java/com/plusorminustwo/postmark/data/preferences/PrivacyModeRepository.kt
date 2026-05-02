package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the global privacy-mode toggle.
 *
 * When privacy mode is on, incoming SMS notifications show "New message"
 * instead of the sender name and message body, so bystanders can't read
 * the screen.
 */
@Singleton
class PrivacyModeRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
        _enabled.value = enabled
    }

    /** Synchronous read — safe to call from a background thread in SmsReceiver. */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY, false)

    companion object {
        private const val KEY = "privacy_mode_enabled"
    }
}
