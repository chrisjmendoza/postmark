package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists unsent reply-bar drafts per thread, so a half-typed message survives
 * leaving the chat, app restarts, and process death (SavedStateHandle alone only
 * covers process death while the screen is still on the back stack).
 *
 * Backed by its own SharedPreferences file keyed by threadId. Drafts are removed
 * on send / when cleared to blank, so the file only ever holds the handful of
 * threads with an actual unfinished message.
 */
@Singleton
class DraftRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_drafts", Context.MODE_PRIVATE)

    /** The saved draft for [threadId], or null when there is none. */
    fun draftFor(threadId: Long): String? = prefs.getString(threadId.toString(), null)

    /** Saves [text] as the draft for [threadId]; blank/null deletes the entry. */
    fun setDraft(threadId: Long, text: String?) {
        prefs.edit().apply {
            if (text.isNullOrBlank()) remove(threadId.toString())
            else putString(threadId.toString(), text)
        }.apply()
    }
}
