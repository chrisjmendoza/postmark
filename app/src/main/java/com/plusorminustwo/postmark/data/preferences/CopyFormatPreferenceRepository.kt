package com.plusorminustwo.postmark.data.preferences

import android.content.Context
import com.plusorminustwo.postmark.domain.customization.CopyFormatOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and exposes [CopyFormatOptions] — what the thread selection bar's Copy
 * action puts on the clipboard — via a [StateFlow]. Backed by SharedPreferences so
 * the choice survives app restarts.
 *
 * Only the clipboard path reads this; the readable ZIP export always archives the
 * full transcript.
 */
@Singleton
class CopyFormatPreferenceRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    private val _options = MutableStateFlow(read())
    val options: StateFlow<CopyFormatOptions> = _options.asStateFlow()

    /** Stores [options] and emits it on [CopyFormatPreferenceRepository.options]. */
    fun set(options: CopyFormatOptions) {
        prefs.edit()
            .putBoolean(KEY_PHONE_NUMBER, options.includePhoneNumber)
            .putBoolean(KEY_DATES, options.includeDates)
            .putBoolean(KEY_TIMESTAMPS, options.includeTimestamps)
            .putBoolean(KEY_REACTIONS, options.includeReactions)
            .putBoolean(KEY_ATTACHMENTS, options.includeAttachments)
            .apply()
        _options.value = options
    }

    private fun read(): CopyFormatOptions {
        val defaults = CopyFormatOptions()
        return CopyFormatOptions(
            includePhoneNumber = prefs.getBoolean(KEY_PHONE_NUMBER, defaults.includePhoneNumber),
            includeDates = prefs.getBoolean(KEY_DATES, defaults.includeDates),
            includeTimestamps = prefs.getBoolean(KEY_TIMESTAMPS, defaults.includeTimestamps),
            includeReactions = prefs.getBoolean(KEY_REACTIONS, defaults.includeReactions),
            includeAttachments = prefs.getBoolean(KEY_ATTACHMENTS, defaults.includeAttachments)
        )
    }

    private companion object {
        const val KEY_PHONE_NUMBER = "copy_format_phone_number"
        const val KEY_DATES = "copy_format_dates"
        const val KEY_TIMESTAMPS = "copy_format_timestamps"
        const val KEY_REACTIONS = "copy_format_reactions"
        const val KEY_ATTACHMENTS = "copy_format_attachments"
    }
}
