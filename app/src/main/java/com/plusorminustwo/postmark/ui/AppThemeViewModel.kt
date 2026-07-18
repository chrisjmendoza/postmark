package com.plusorminustwo.postmark.ui

import androidx.lifecycle.ViewModel
import com.plusorminustwo.postmark.data.preferences.FontFamilyPreferenceRepository
import com.plusorminustwo.postmark.data.preferences.ThemePreferenceRepository
import com.plusorminustwo.postmark.ui.theme.FontFamilyPreference
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the current [ThemePreference] and [FontFamilyPreference] as [StateFlow]s for
 * consumption by the root Compose tree. Backed by [ThemePreferenceRepository] and
 * [FontFamilyPreferenceRepository].
 */
@HiltViewModel
class AppThemeViewModel @Inject constructor(
    themeRepo: ThemePreferenceRepository,
    fontFamilyRepo: FontFamilyPreferenceRepository
) : ViewModel() {
    val themePreference: StateFlow<ThemePreference> = themeRepo.preference
    val fontFamilyPreference: StateFlow<FontFamilyPreference> = fontFamilyRepo.preference
}
