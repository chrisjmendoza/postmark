package com.plusorminustwo.postmark.ui.settings

import androidx.lifecycle.ViewModel
import com.plusorminustwo.postmark.data.preferences.ThemePreferenceRepository
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themeRepo: ThemePreferenceRepository
) : ViewModel() {
    val themePreference: StateFlow<ThemePreference> = themeRepo.preference

    fun setTheme(pref: ThemePreference) = themeRepo.set(pref)
}
