package com.plusorminustwo.postmark.data.sync

/** Raw data extracted from one row of content://mms/$id/part. */
internal data class MmsRawPart(
    val id: Long,
    val contentType: String,
    val text: String?
)

/** Result of parsing all parts for one MMS PDU. */
internal data class MmsParsedResult(
    val body: String,
    val attachmentUri: String?,
    val mimeType: String?
)

/**
 * Pure function: turns a list of raw MMS parts into a [MmsParsedResult].
 *
 * Rules applied (matching Android MMS spec):
 * - `text/plain` parts are concatenated to form the message body.
 * - `application/smil` parts are skipped — presentation metadata only.
 * - The first `image/`, `video/`, or `audio/` part wins as the attachment.
 *   Subsequent media parts are ignored (single-attachment limitation).
 * - Unknown content types are ignored.
 *
 * Separated from the content-resolver layer so it can be unit-tested
 * on the JVM without Android instrumentation.
 */
internal fun parseMmsRawParts(parts: List<MmsRawPart>): MmsParsedResult {
    val sb = StringBuilder()
    var attachmentUri: String? = null
    var mimeType: String? = null

    for (part in parts) {
        val ct = part.contentType
        when {
            ct.equals("text/plain", ignoreCase = true) ->
                sb.append(part.text ?: "")

            ct.equals("application/smil", ignoreCase = true) -> Unit

            ct.startsWith("image/", ignoreCase = true) ||
            ct.startsWith("video/", ignoreCase = true) ||
            ct.startsWith("audio/", ignoreCase = true) -> {
                if (attachmentUri == null) {
                    attachmentUri = "content://mms/part/${part.id}"
                    mimeType = ct
                }
            }

            else -> Unit
        }
    }

    return MmsParsedResult(sb.toString().trim(), attachmentUri, mimeType)
}
