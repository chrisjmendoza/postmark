package com.plusorminustwo.postmark.data.sync

import android.content.ContentResolver
import android.net.Uri

/**
 * Streams the content of a file-backed MMS text part (`_data` set, `text` column null).
 *
 * The provider stores such parts on disk — notably Google Messages' RCS archival rows,
 * which is how RCS reaction fallbacks (`❤️ to "…"`) reach a non-RCS SMS app. Reading
 * only the part table's `text` column imported those as empty bodies, which rendered
 * as blank bubbles and could never resolve into reactions.
 *
 * Decoded as UTF-8: every observed archival/persist path writes UTF-8 (PduPersister
 * charset 106). A wrongly-decoded body is still strictly better than a silently empty
 * one, and the `chset` column is not reliably populated across OEMs.
 *
 * Returns null on any failure or blank content so callers can treat "unreadable" and
 * "absent" identically.
 */
internal fun ContentResolver.readMmsPartText(partId: Long): String? = try {
    openInputStream(Uri.parse("content://mms/part/$partId"))
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}
