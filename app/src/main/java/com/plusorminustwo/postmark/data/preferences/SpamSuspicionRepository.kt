package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which threads have had their "Looks like spam?" banner dismissed, so a
 * once-waved-away banner never returns for that thread.
 *
 * Suspicion itself is recomputed live from the thread's first message (see
 * [com.plusorminustwo.postmark.domain.spam.looksLikeSpam]) — there is no schema change and
 * nothing here about *why* a thread looked like spam. Only the user's dismissal decision
 * is persisted, one boolean per threadId, in this repository's own SharedPreferences file
 * (pattern-matches [DraftRepository]). The file only ever holds the handful of threads a
 * banner was actually dismissed for.
 */
@Singleton
class SpamSuspicionRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_spam_suspicion", Context.MODE_PRIVATE)

    /** Whether the suspected-spam banner has been dismissed for [threadId]. */
    fun isDismissed(threadId: Long): Boolean = prefs.getBoolean(threadId.toString(), false)

    /** Permanently dismisses the suspected-spam banner for [threadId]. */
    fun dismiss(threadId: Long) {
        prefs.edit().putBoolean(threadId.toString(), true).apply()
    }
}
