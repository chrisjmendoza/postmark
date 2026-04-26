package com.plusorminustwo.postmark.ui

import androidx.lifecycle.ViewModel
import com.plusorminustwo.postmark.data.preferences.ThemePreferenceRepository
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AppThemeViewModel @Inject constructor(
    repo: ThemePreferenceRepository
) : ViewModel() {
    val themePreference: StateFlow<ThemePreference> = repo.preference
}
