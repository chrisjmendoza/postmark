package com.plusorminustwo.postmark.ui.settings

import androidx.lifecycle.ViewModel
import com.plusorminustwo.postmark.data.preferences.BubbleFontScaleRepository
import com.plusorminustwo.postmark.data.preferences.PrivacyModeRepository
import com.plusorminustwo.postmark.data.preferences.ThemePreferenceRepository
import com.plusorminustwo.postmark.data.preferences.TimestampPreferenceRepository
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 *
 * Exposes [themePreference], [timestampPreference], and [privacyModeEnabled] as
 * read-only [StateFlow]s and provides a setter for each. All persistence is
 * delegated to the respective repository.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themeRepo: ThemePreferenceRepository,
    private val timestampRepo: TimestampPreferenceRepository,
    private val privacyModeRepo: PrivacyModeRepository,
    private val fontScaleRepo: BubbleFontScaleRepository
) : ViewModel() {
    val themePreference: StateFlow<ThemePreference> = themeRepo.preference
    val timestampPreference: StateFlow<TimestampPreference> = timestampRepo.preference
    val privacyModeEnabled: StateFlow<Boolean> = privacyModeRepo.enabled

    // ── Bubble font scale ─────────────────────────────────────────────────────
    /** Current bubble text size multiplier (0.8 – 1.6). Shared with ThreadViewModel. */
    val bubbleFontScale: StateFlow<Float> = fontScaleRepo.scale

    /** Updates the app-wide colour theme. Change is persisted and reflected immediately. */
    fun setTheme(pref: ThemePreference) = themeRepo.set(pref)

    /** Updates the timestamp display mode. Change is persisted and reflected immediately. */
    fun setTimestamp(pref: TimestampPreference) = timestampRepo.set(pref)

    /** Enables or disables privacy mode (hides message previews in the conversation list). */
    fun setPrivacyMode(enabled: Boolean) = privacyModeRepo.set(enabled)

    /** Sets font scale to an absolute value (clamped 0.8–1.6). Called from the settings slider. */
    fun setBubbleFontScale(value: Float) = fontScaleRepo.set(value)

    /** Resets font scale to the default (1.0). */
    fun resetBubbleFontScale() = fontScaleRepo.reset()
}
