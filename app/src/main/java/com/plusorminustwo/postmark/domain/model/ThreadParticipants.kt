package com.plusorminustwo.postmark.domain.model

/*
 * JSON codec for persisting a List<String> of group-MMS participant addresses in a
 * single TEXT column (threads.participantsJson), following the same *Json column
 * convention as MessageAttachment's attachmentsJson.
 *
 * Hand-written for the same reason as encodeAttachmentsJson/decodeAttachmentsJson:
 * org.json is an unmocked stub in JVM unit tests. The decoder only needs to
 * understand what the encoder emits: ["addr1","addr2",...].
 */

/** Serializes [participants] to a JSON string array, or null when there's 0 or 1
 *  addresses (so ordinary 1:1 threads keep a NULL column instead of storing "[]"). */
fun encodeParticipantsJson(participants: List<String>): String? =
    if (participants.size <= 1) null
    else participants.joinToString(separator = ",", prefix = "[", postfix = "]") {
        "\"${escapeJson(it)}\""
    }

/** Parses a string produced by [encodeParticipantsJson]. Returns an empty list for
 *  null/blank input or malformed JSON. */
fun decodeParticipantsJson(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    val result = mutableListOf<String>()
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
            result += sb.toString()
        }
        i++
    }
    return result
}
