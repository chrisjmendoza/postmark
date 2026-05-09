package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's bubble font-scale multiplier (set via pinch-to-zoom in the thread view).
 *
 * Scale range: 0.8 – 1.6 (clamped on write). Default: 1.0.
 * Backed by SharedPreferences so it survives app restarts.
 * Exposed as a [StateFlow] so [ThreadViewModel] can pass it down reactively.
 */
@Singleton
class BubbleFontScaleRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    // ── Constants ─────────────────────────────────────────────────────────────
    companion object {
        const val MIN_SCALE     = 0.8f
        const val MAX_SCALE     = 1.6f
        const val DEFAULT_SCALE = 1.0f
        private const val PREF_KEY = "bubble_font_scale"
    }

    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    // ── Live state ────────────────────────────────────────────────────────────
    private val _scale = MutableStateFlow(readScale())
    val scale: StateFlow<Float> = _scale.asStateFlow()

    /** Adjusts the font scale by [delta] (positive = bigger, negative = smaller) and persists. */
    fun adjust(delta: Float) {
        val newScale = (_scale.value + delta).coerceIn(MIN_SCALE, MAX_SCALE)
        _scale.value = newScale
        prefs.edit().putFloat(PREF_KEY, newScale).apply()
    }

    /** Sets font scale to an absolute [value] (clamped to MIN_SCALE..MAX_SCALE) and persists. */
    fun set(value: Float) {
        val clamped = value.coerceIn(MIN_SCALE, MAX_SCALE)
        _scale.value = clamped
        prefs.edit().putFloat(PREF_KEY, clamped).apply()
    }

    /** Resets font scale to 1.0 and persists. */
    fun reset() {
        _scale.value = DEFAULT_SCALE
        prefs.edit().putFloat(PREF_KEY, DEFAULT_SCALE).apply()
    }

    // ── Internal ──────────────────────────────────────────────────────────────
    private fun readScale(): Float =
        prefs.getFloat(PREF_KEY, DEFAULT_SCALE).coerceIn(MIN_SCALE, MAX_SCALE)
}
