package com.plusorminustwo.postmark.ui.settings

import androidx.lifecycle.ViewModel
import com.plusorminustwo.postmark.data.preferences.PrivacyModeRepository
import com.plusorminustwo.postmark.data.preferences.ThemePreferenceRepository
import com.plusorminustwo.postmark.data.preferences.TimestampPreferenceRepository
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themeRepo: ThemePreferenceRepository,
    private val timestampRepo: TimestampPreferenceRepository,
    private val privacyModeRepo: PrivacyModeRepository
) : ViewModel() {
    val themePreference: StateFlow<ThemePreference> = themeRepo.preference
    val timestampPreference: StateFlow<TimestampPreference> = timestampRepo.preference
    val privacyModeEnabled: StateFlow<Boolean> = privacyModeRepo.enabled

    fun setTheme(pref: ThemePreference) = themeRepo.set(pref)
    fun setTimestamp(pref: TimestampPreference) = timestampRepo.set(pref)
    fun setPrivacyMode(enabled: Boolean) = privacyModeRepo.set(enabled)
}
