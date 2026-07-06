package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.domain.model.MessageAttachment

/** Raw data extracted from one row of content://mms/$id/part. */
internal data class MmsRawPart(
    val id: Long,
    val contentType: String,
    val text: String?
)

/** Result of parsing all parts for one MMS PDU. */
internal data class MmsParsedResult(
    val body: String,
    val attachments: List<MessageAttachment>
) {
    /** Human-readable thread preview: emoji label for media-only messages. */
    val previewText: String
        get() {
            val firstMime = attachments.firstOrNull()?.mimeType
            return when {
                body.isNotEmpty()                        -> body
                firstMime?.startsWith("image/") == true  -> "📷 Photo"
                firstMime?.startsWith("video/") == true  -> "🎥 Video"
                firstMime?.startsWith("audio/") == true  -> "🎵 Audio message"
                else                                     -> "[MMS]"
            }
        }
}

/**
 * Pure function: turns a list of raw MMS parts into a [MmsParsedResult].
 *
 * Rules applied (matching Android MMS spec):
 * - `text/plain` parts are concatenated to form the message body.
 * - `application/smil` parts are skipped — presentation metadata only.
 * - Every `image/`, `video/`, or `audio/` part becomes an attachment with a stable
 *   `content://mms/part/{id}` URI, preserving PDU order. Matching is case-insensitive
 *   for Samsung and other OEMs that use mixed-case MIME types like `audio/AMR`.
 * - Unknown content types are ignored.
 *
 * Separated from the content-resolver layer so it can be unit-tested
 * on the JVM without Android instrumentation.
 */
internal fun parseMmsRawParts(parts: List<MmsRawPart>): MmsParsedResult {
    val sb = StringBuilder()
    val attachments = mutableListOf<MessageAttachment>()

    for (part in parts) {
        val ct = part.contentType
        when {
            ct.equals("text/plain", ignoreCase = true) ->
                sb.append(part.text ?: "")

            ct.equals("application/smil", ignoreCase = true) -> Unit

            ct.startsWith("image/", ignoreCase = true) ||
            ct.startsWith("video/", ignoreCase = true) ||
            ct.startsWith("audio/", ignoreCase = true) ->
                attachments += MessageAttachment("content://mms/part/${part.id}", ct)

            else -> Unit
        }
    }

    return MmsParsedResult(sb.toString().trim(), attachments)
}
