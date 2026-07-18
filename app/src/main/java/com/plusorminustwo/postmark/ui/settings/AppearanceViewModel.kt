package com.plusorminustwo.postmark.ui.settings

import androidx.lifecycle.ViewModel
import com.plusorminustwo.postmark.data.preferences.BubbleFontScaleRepository
import com.plusorminustwo.postmark.data.preferences.ChatBackgroundPreferenceRepository
import com.plusorminustwo.postmark.data.preferences.FontFamilyPreferenceRepository
import com.plusorminustwo.postmark.data.preferences.ThemePreferenceRepository
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds
import com.plusorminustwo.postmark.ui.theme.FontFamilyPreference
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for the Appearance screen.
 *
 * Owns every app-wide appearance preference: theme, font family, bubble font scale,
 * and the global default chat background. All persistence is delegated to the
 * respective repository.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val themeRepo: ThemePreferenceRepository,
    private val fontFamilyRepo: FontFamilyPreferenceRepository,
    private val fontScaleRepo: BubbleFontScaleRepository,
    private val chatBackgroundRepo: ChatBackgroundPreferenceRepository
) : ViewModel() {
    val themePreference: StateFlow<ThemePreference> = themeRepo.preference
    val fontFamilyPreference: StateFlow<FontFamilyPreference> = fontFamilyRepo.preference

    /** Current bubble text size multiplier (0.8 – 1.6). Shared with ThreadViewModel. */
    val bubbleFontScale: StateFlow<Float> = fontScaleRepo.scale

    /** Global default chat-background id; null = none. Shared with ThreadViewModel. */
    val globalChatBackgroundId: StateFlow<String?> = chatBackgroundRepo.backgroundId

    /** Updates the app-wide colour theme. Change is persisted and reflected immediately. */
    fun setTheme(pref: ThemePreference) = themeRepo.set(pref)

    /** Updates the app-wide font family. Change is persisted and reflected immediately. */
    fun setFontFamily(pref: FontFamilyPreference) = fontFamilyRepo.set(pref)

    /** Sets font scale to an absolute value (clamped 0.8–1.6). Called from the settings slider. */
    fun setBubbleFontScale(value: Float) = fontScaleRepo.set(value)

    /** Resets font scale to the default (1.0). */
    fun resetBubbleFontScale() = fontScaleRepo.reset()

    /** Sets the global default chat background. This picker has no "Default" option, so
     *  [ChatBackgrounds.None]'s id is normalized to null — the repository's own "unset"
     *  value — keeping only one representation of "no background". */
    fun setChatBackground(id: String?) =
        chatBackgroundRepo.set(ChatBackgrounds.toGlobalPreferenceId(id))
}
