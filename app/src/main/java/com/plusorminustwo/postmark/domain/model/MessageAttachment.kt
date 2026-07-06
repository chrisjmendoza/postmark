package com.plusorminustwo.postmark.domain.model

import androidx.compose.runtime.Immutable

/**
 * One media attachment of an MMS message.
 *
 * @param uri      Content URI of the media part — `content://mms/part/{id}` for synced
 *                 messages, a FileProvider or photo-picker URI for outgoing ones.
 * @param mimeType MIME type of the part (e.g. `image/jpeg`, `video/mp4`, `audio/amr`).
 */
@Immutable
data class MessageAttachment(
    val uri: String,
    val mimeType: String
)

/*
 * JSON codec for persisting a List<MessageAttachment> in a single TEXT column
 * (messages.attachmentsJson), following the *Json column convention used by the
 * stats entities (topEmojisJson etc.).
 *
 * Hand-written rather than org.json because org.json is an unmocked stub in JVM
 * unit tests, and these are exactly the kind of pure functions this project tests.
 * The decoder only needs to understand what the encoder emits:
 *   [{"uri":"...","mimeType":"..."},...]
 * with backslash and double-quote escaped inside string values.
 */

/** Serializes [attachments] to a JSON array string, or null when the list is empty
 *  (so SMS / text-only MMS rows keep a NULL column instead of storing "[]"). */
fun encodeAttachmentsJson(attachments: List<MessageAttachment>): String? =
    if (attachments.isEmpty()) null
    else attachments.joinToString(separator = ",", prefix = "[", postfix = "]") {
        """{"uri":"${escapeJson(it.uri)}","mimeType":"${escapeJson(it.mimeType)}"}"""
    }

/** Parses a string produced by [encodeAttachmentsJson]. Returns an empty list for
 *  null/blank input or anything that doesn't yield complete uri+mimeType pairs. */
fun decodeAttachmentsJson(json: String?): List<MessageAttachment> {
    if (json.isNullOrBlank()) return emptyList()
    // Extract all string literals in order: [key, value, key, value, ...]
    val strings = mutableListOf<String>()
    var i = 0
    while (i < json.length) {
        if (json[i] == '"') {
            val sb = StringBuilder()
            i++
            while (i < json.length && json[i] != '"') {
                if (json[i] == '\\' && i + 1 < json.length) {
                    sb.append(json[i + 1]); i += 2
                } else {
                    sb.append(json[i]); i++
                }
            }
            strings += sb.toString()
        }
        i++
    }
    // Pair keys with values; emit an attachment each time both fields are present.
    val result = mutableListOf<MessageAttachment>()
    var uri: String? = null
    var mime: String? = null
    var idx = 0
    while (idx + 1 < strings.size) {
        when (strings[idx]) {
            "uri"      -> uri  = strings[idx + 1]
            "mimeType" -> mime = strings[idx + 1]
        }
        idx += 2
        if (uri != null && mime != null) {
            result += MessageAttachment(uri, mime)
            uri = null
            mime = null
        }
    }
    return result
}

internal fun escapeJson(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '\\' -> append("\\\\")
        '"'  -> append("\\\"")
        else -> append(c)
    }
}
