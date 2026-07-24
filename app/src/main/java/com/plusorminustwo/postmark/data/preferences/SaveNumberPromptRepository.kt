package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which threads have had their "Add to contacts?" banner dismissed, so a
 * once-waved-away banner never returns for that thread.
 *
 * Whether the banner is offered at all is recomputed live from the thread's current
 * address/contact state (see [com.plusorminustwo.postmark.domain.contacts.shouldShowSaveNumberPrompt])
 * — there is no schema change and nothing here about the address itself. Only the user's
 * dismissal decision is persisted, one boolean per threadId, in this repository's own
 * SharedPreferences file (pattern-matches [SpamSuspicionRepository]).
 */
@Singleton
class SaveNumberPromptRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_save_number_prompt", Context.MODE_PRIVATE)

    /** Whether the "Add to contacts?" banner has been dismissed for [threadId]. */
    fun isDismissed(threadId: Long): Boolean = prefs.getBoolean(threadId.toString(), false)

    /** Permanently dismisses the "Add to contacts?" banner for [threadId]. */
    fun dismiss(threadId: Long) {
        prefs.edit().putBoolean(threadId.toString(), true).apply()
    }
}
