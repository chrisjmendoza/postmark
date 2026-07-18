package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the one-time gesture-discovery hint flags. Every power gesture in Postmark
 * (swipe-to-reply, long-press-to-react, pinch-to-resize-text, long-press multi-select)
 * is otherwise invisible; these flags let the UI surface a hint exactly once and then
 * stay out of the way forever.
 *
 * Three fully independent flags, each flipping once — from "not yet shown/dismissed" to
 * "done", never back:
 *   • [threadTipsDismissed]        — the thread gesture-tips card was dismissed.
 *   • [reactionsLocalNoticeShown]  — the "reactions stay on your phone" notice was shown.
 *   • [multiSelectHintDismissed]   — the conversations multi-select hint row was dismissed.
 *
 * Mirrors [BubbleFontScaleRepository]: a Hilt singleton over the shared "postmark_prefs"
 * SharedPreferences, each flag exposed as a [StateFlow] so the ViewModels can pass it down
 * reactively. Unlike the font scale these are one-shot boolean writes (not per-frame), so
 * they persist synchronously via apply() with no debounce.
 */
@Singleton
class GestureHintsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    // ── Thread gesture-tips card (swipe-to-reply / long-press-react / pinch-to-resize) ──
    private val _threadTipsDismissed = MutableStateFlow(prefs.getBoolean(KEY_THREAD_TIPS, false))
    val threadTipsDismissed: StateFlow<Boolean> = _threadTipsDismissed.asStateFlow()

    fun markThreadTipsDismissed() = mark(KEY_THREAD_TIPS, _threadTipsDismissed)

    // ── "Reactions are local-only" one-time notice ─────────────────────────────
    private val _reactionsLocalNoticeShown = MutableStateFlow(prefs.getBoolean(KEY_REACTIONS_NOTICE, false))
    val reactionsLocalNoticeShown: StateFlow<Boolean> = _reactionsLocalNoticeShown.asStateFlow()

    fun markReactionsLocalNoticeShown() = mark(KEY_REACTIONS_NOTICE, _reactionsLocalNoticeShown)

    // ── Conversations multi-select hint row ────────────────────────────────────
    private val _multiSelectHintDismissed = MutableStateFlow(prefs.getBoolean(KEY_MULTI_SELECT_HINT, false))
    val multiSelectHintDismissed: StateFlow<Boolean> = _multiSelectHintDismissed.asStateFlow()

    fun markMultiSelectHintDismissed() = mark(KEY_MULTI_SELECT_HINT, _multiSelectHintDismissed)

    /** Flips a one-time flag true and persists it. Idempotent — a repeat call is a
     *  harmless no-op write. */
    private fun mark(key: String, flow: MutableStateFlow<Boolean>) {
        prefs.edit().putBoolean(key, true).apply()
        flow.value = true
    }

    private companion object {
        const val KEY_THREAD_TIPS       = "gesture_hint_thread_tips_dismissed"
        const val KEY_REACTIONS_NOTICE  = "gesture_hint_reactions_local_shown"
        const val KEY_MULTI_SELECT_HINT = "gesture_hint_multiselect_dismissed"
    }
}
