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
 * FALLBACK PATH ONLY. The primary roster source is the OS's canonical threads table
 * (GROUP_MESSAGING_SPEC §1.3, see [queryCanonicalParticipants]); this per-PDU scan is
 * used only when the canonical query can't answer. It filters the Samsung
 * "insert-address-token" placeholder and blank addresses, but cannot reliably exclude
 * the local device's own number — an incoming 1:1 M-Retrieve.conf carries a TO row for
 * the user's own line, so self appears in the roster and flips a genuine 1:1 thread
 * into a 2-person "group". That is a known correctness defect of this fallback (not a
 * cosmetic one); the canonical path avoids it because AOSP's PduPersister excludes the
 * device's own line from `recipient_ids`.
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

// content://mms `m_type` PDU header values relevant to display filtering (WAP MMS
// Encapsulation spec §6.1.2). Every row in content://mms is one PDU, not just a
// user-composed message — the same table also stores M-Notification.ind (130, "you
// have an MMS, fetch it"), delivery-report (134) and read-report (136) PDUs. Those
// carry no text/media parts and previously imported as empty "Unknown" bubbles
// (GROUP_MESSAGING_SPEC §4.2).
internal const val PDU_MTYPE_SEND_REQ      = 128 // m-send-req: outgoing MMS the user composed
internal const val PDU_MTYPE_RETRIEVE_CONF = 132 // m-retrieve-conf: a fully downloaded incoming MMS

/**
 * Pure function: whether an MMS PDU row should be imported as a visible message.
 *
 * Only Send.req (128) and Retrieve.conf (132) carry actual message content; every
 * other `m_type` is protocol bookkeeping. A null [mType] is treated as displayable —
 * some OEMs omit the column, and dropping on missing data would silently lose real
 * messages rather than just filtering junk.
 */
internal fun isDisplayableMmsType(mType: Int?): Boolean =
    mType == null || mType == PDU_MTYPE_SEND_REQ || mType == PDU_MTYPE_RETRIEVE_CONF
