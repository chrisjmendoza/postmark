package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and exposes the conversation-list (home screen) background id via a [StateFlow].
 * Null means no background — the plain theme background, which is what an un-customized
 * install renders.
 *
 * Deliberately separate from [ChatBackgroundPreferenceRepository] rather than a shared
 * "background" value: the home screen and a conversation are different surfaces, and a user
 * who sets a photo behind their threads rarely wants the same photo behind the inbox list.
 * Both draw ids from the SAME vocabulary though — a [com.plusorminustwo.postmark.domain.customization.ChatBackgrounds]
 * catalog id or an `image:<fileName>` id — so the picker, the placement editor, and the
 * image store are all reused unchanged.
 *
 * That shared vocabulary is also why [com.plusorminustwo.postmark.service.customization.ChatBackgroundImageStore]
 * consults this repository before garbage-collecting an image: the same file can be the home
 * background and nothing else, and dropping it on a chat-background change would blank the
 * home screen.
 *
 * Call [set] to update both the persisted value and the live [backgroundId] flow.
 */
@Singleton
class HomeBackgroundPreferenceRepository @Inject constructor(
    @ApplicationContext context: Context
) : BackgroundIdPreference {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    private val _backgroundId = MutableStateFlow(read())
    override val backgroundId: StateFlow<String?> = _backgroundId.asStateFlow()

    /** Updates the stored home-screen background id and emits the new value on
     *  [backgroundId]; pass null to clear it. */
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
        private const val KEY = "home_background"
    }
}
