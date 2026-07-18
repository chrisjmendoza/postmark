package com.plusorminustwo.postmark.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.preferences.AppAccentPreferenceRepository
import com.plusorminustwo.postmark.data.preferences.BubbleFontScaleRepository
import com.plusorminustwo.postmark.data.preferences.BubbleStylePreferenceRepository
import com.plusorminustwo.postmark.data.preferences.ChatBackgroundPreferenceRepository
import com.plusorminustwo.postmark.data.preferences.DynamicColorPreferenceRepository
import com.plusorminustwo.postmark.data.preferences.FontFamilyPreferenceRepository
import com.plusorminustwo.postmark.data.preferences.ThemePreferenceRepository
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds
import com.plusorminustwo.postmark.service.customization.ChatBackgroundImageStore
import com.plusorminustwo.postmark.ui.theme.BubbleStylePreference
import com.plusorminustwo.postmark.ui.theme.FontFamilyPreference
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for the Appearance screen.
 *
 * Owns every app-wide appearance preference: theme, font family, bubble font scale,
 * bubble shape style, Material You dynamic color, the global app accent, and the
 * global default chat background. All persistence is delegated to the respective
 * repository.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val themeRepo: ThemePreferenceRepository,
    private val fontFamilyRepo: FontFamilyPreferenceRepository,
    private val fontScaleRepo: BubbleFontScaleRepository,
    private val bubbleStyleRepo: BubbleStylePreferenceRepository,
    private val chatBackgroundRepo: ChatBackgroundPreferenceRepository,
    private val dynamicColorRepo: DynamicColorPreferenceRepository,
    private val appAccentRepo: AppAccentPreferenceRepository,
    private val imageStore: ChatBackgroundImageStore
) : ViewModel() {
    val themePreference: StateFlow<ThemePreference> = themeRepo.preference
    val fontFamilyPreference: StateFlow<FontFamilyPreference> = fontFamilyRepo.preference

    /** Current bubble text size multiplier (0.8 – 1.6). Shared with ThreadViewModel. */
    val bubbleFontScale: StateFlow<Float> = fontScaleRepo.scale

    /** Current bubble shape style (rounded / pill / square). Shared with ThreadViewModel. */
    val bubbleStyle: StateFlow<BubbleStylePreference> = bubbleStyleRepo.preference

    /** Global default chat-background id; null = none. Shared with ThreadViewModel. */
    val globalChatBackgroundId: StateFlow<String?> = chatBackgroundRepo.backgroundId

    /** Whether Material You (wallpaper-derived dynamic color) is enabled. Only applied
     *  on API 31+; the Appearance screen hides this row below that. */
    val useDynamicColor: StateFlow<Boolean> = dynamicColorRepo.enabled

    /** Global app accent (packed ARGB); null = Postmark's own brand blue. Ignored by
     *  [com.plusorminustwo.postmark.ui.theme.PostmarkTheme] while Material You is on.
     *  Shared with ThreadViewModel (sent-bubble default content color fallback). */
    val appAccentArgb: StateFlow<Int?> = appAccentRepo.accentArgb

    /** Updates the app-wide colour theme. Change is persisted and reflected immediately. */
    fun setTheme(pref: ThemePreference) = themeRepo.set(pref)

    /** Updates the app-wide font family. Change is persisted and reflected immediately. */
    fun setFontFamily(pref: FontFamilyPreference) = fontFamilyRepo.set(pref)

    /** Toggles Material You dynamic color. Change is persisted and reflected immediately. */
    fun setDynamicColor(enabled: Boolean) = dynamicColorRepo.set(enabled)

    /** Sets font scale to an absolute value (clamped 0.8–1.6). Called from the settings slider. */
    fun setBubbleFontScale(value: Float) = fontScaleRepo.set(value)

    /** Resets font scale to the default (1.0). */
    fun resetBubbleFontScale() = fontScaleRepo.reset()

    /** Updates the app-wide bubble shape style. Change is persisted and reflected immediately. */
    fun setBubbleStyle(pref: BubbleStylePreference) = bubbleStyleRepo.set(pref)

    /** Updates the global app accent; pass null to reset to the default brand blue. */
    fun setAppAccent(argb: Int?) = appAccentRepo.set(argb)

    /** Sets the global default chat background. This picker has no "Default" option, so
     *  [ChatBackgrounds.None]'s id is normalized to null — the repository's own "unset"
     *  value — keeping only one representation of "no background". */
    fun setChatBackground(id: String?) =
        applyGlobalBackground(ChatBackgrounds.toGlobalPreferenceId(id))

    /**
     * Picks up a gallery image: copies + downscales it into app storage, then sets the
     * resulting `image:<fileName>` id as the global default. A save failure is a silent
     * no-op beyond the store's own log (v1-simple).
     */
    fun setImageBackground(uri: Uri) {
        viewModelScope.launch {
            val id = imageStore.save(uri) ?: return@launch
            applyGlobalBackground(id)
        }
    }

    /** Resolved file for a custom-image [id] (for the dialog thumbnail); null if missing. */
    fun chatBackgroundImageFile(id: String): File? = imageStore.fileFor(id)

    /** Overwrites the global default, then lets the store garbage-collect the PREVIOUS
     *  image if it was a custom one now referenced by nothing. */
    private fun applyGlobalBackground(newId: String?) {
        val old = chatBackgroundRepo.backgroundId.value
        chatBackgroundRepo.set(newId)
        viewModelScope.launch { imageStore.cleanupAfterChange(old, newId) }
    }
}
