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

/** One row of content://mms/$id/addr: an address plus its PDU header type. */
internal data class MmsAddrRow(val address: String?, val type: Int)

// PduHeaders values relevant to MMS participants (WAP spec).
internal const val PDU_HEADER_FROM = 137
internal const val PDU_HEADER_TO   = 151
internal const val PDU_HEADER_CC   = 130

/**
 * Pure function: collapses every FROM/TO/CC row for one MMS PDU into an ordered,
 * deduplicated list of participant addresses — the full roster of a group MMS
 * (MMS_AUDIT §2.3: previously only the first FROM/TO row was read, so every other
 * participant was silently dropped).
 *
 * Filters the Samsung "insert-address-token" placeholder and blank addresses.
 * Cannot reliably exclude the local device's own number — no row in the PDU's
 * addr table identifies "this is you" — so it may occasionally appear in the
 * roster. That's a cosmetic imperfection, not a correctness bug: every real
 * participant is preserved, which is the property that matters here.
 */
internal fun parseMmsParticipants(rows: List<MmsAddrRow>): List<String> {
    val seen = LinkedHashSet<String>()
    for (row in rows) {
        if (row.type != PDU_HEADER_FROM && row.type != PDU_HEADER_TO && row.type != PDU_HEADER_CC) continue
        val addr = row.address
        if (addr.isNullOrBlank() || addr == "insert-address-token") continue
        seen += addr
    }
    return seen.toList()
}
