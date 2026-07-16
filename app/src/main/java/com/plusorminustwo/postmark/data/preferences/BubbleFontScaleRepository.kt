package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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

    // Debounced persistence: adjust() fires per pinch-gesture FRAME (and the settings
    // slider per drag frame). An Editor.apply() per frame floods QueuedWork, whose
    // pending writes are flushed synchronously at Activity.onStop — a classic source
    // of lifecycle-transition jank. collectLatest + delay = debounce; drop(1) skips
    // re-persisting the value we just read at construction. Losing the final ~400 ms
    // of a pinch to process death is acceptable for a display preference.
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        persistScope.launch {
            _scale.drop(1).collectLatest { value ->
                delay(400)
                prefs.edit().putFloat(PREF_KEY, value).apply()
            }
        }
    }

    /** Adjusts the font scale by [delta] (positive = bigger, negative = smaller) and persists. */
    fun adjust(delta: Float) {
        _scale.value = (_scale.value + delta).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** Sets font scale to an absolute [value] (clamped to MIN_SCALE..MAX_SCALE) and persists. */
    fun set(value: Float) {
        _scale.value = value.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** Resets font scale to 1.0 and persists. */
    fun reset() {
        _scale.value = DEFAULT_SCALE
    }

    // ── Internal ──────────────────────────────────────────────────────────────
    private fun readScale(): Float =
        prefs.getFloat(PREF_KEY, DEFAULT_SCALE).coerceIn(MIN_SCALE, MAX_SCALE)
}
