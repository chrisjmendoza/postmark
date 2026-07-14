package com.plusorminustwo.postmark.domain.backup

/*
 * Minimal JSON codec for the v2 backup format.
 *
 * Hand-written for the same reason as the MessageAttachment/ThreadParticipants codecs:
 * org.json is an unmocked stub in JVM unit tests (and android.util.JsonWriter is
 * Android-only), while this encode/decode logic is exactly the kind of pure function
 * this project keeps testable on the JVM.
 *
 * Unlike those single-purpose codecs this is a complete (if small) JSON implementation,
 * because backup records nest arrays/objects, and message bodies contain newlines —
 * in the line-delimited data.jsonl stream an unescaped control character would destroy
 * record framing, so strings are fully escaped per RFC 8259.
 *
 * Value mapping: object ↔ Map<String, Any?>, array ↔ List<Any?>, string ↔ String,
 * integral number ↔ Long, fractional ↔ Double, true/false ↔ Boolean, null ↔ null.
 */

/** Serializes [value] to compact JSON. Accepts null, Boolean, Int, Long, Double,
 *  String, List and Map with String keys; anything else is a programming error. */
fun encodeJson(value: Any?): String = buildString { appendJsonValue(value) }

private fun StringBuilder.appendJsonValue(value: Any?) {
    when (value) {
        null          -> append("null")
        is Boolean    -> append(if (value) "true" else "false")
        is Int        -> append(value.toString())
        is Long       -> append(value.toString())
        is Double     -> {
            require(value.isFinite()) { "Cannot encode non-finite number" }
            append(value.toString())
        }
        is String     -> appendJsonString(value)
        is List<*>    -> {
            append('[')
            value.forEachIndexed { i, v ->
                if (i > 0) append(',')
                appendJsonValue(v)
            }
            append(']')
        }
        is Map<*, *>  -> {
            append('{')
            var first = true
            for ((k, v) in value) {
                require(k is String) { "JSON object keys must be strings, got: $k" }
                if (!first) append(',')
                first = false
                appendJsonString(k)
                append(':')
                appendJsonValue(v)
            }
            append('}')
        }
        else          -> throw IllegalArgumentException(
            "Cannot encode ${value::class.simpleName} as JSON"
        )
    }
}

private fun StringBuilder.appendJsonString(s: String) {
    append('"')
    for (c in s) when {
        c == '\\'     -> append("\\\\")
        c == '"'      -> append("\\\"")
        c == '\n'     -> append("\\n")
        c == '\r'     -> append("\\r")
        c == '\t'     -> append("\\t")
        c.code < 0x20 -> append("\\u%04x".format(c.code))
        else          -> append(c)
    }
    append('"')
}

/** Parses a complete JSON document. Throws [IllegalArgumentException] on malformed
 *  input or trailing content — callers processing untrusted lines catch per line. */
fun parseJson(text: String): Any? {
    val parser = JsonParser(text)
    val value = parser.parseValue()
    parser.skipWhitespace()
    require(parser.atEnd()) { "Trailing content after JSON value at ${parser.pos}" }
    return value
}

private class JsonParser(private val text: String) {
    var pos = 0
        private set

    fun atEnd(): Boolean = pos >= text.length

    fun skipWhitespace() {
        while (pos < text.length && text[pos] in " \t\n\r") pos++
    }

    fun parseValue(): Any? {
        skipWhitespace()
        require(!atEnd()) { "Unexpected end of JSON input" }
        return when (text[pos]) {
            '{'  -> parseObject()
            '['  -> parseArray()
            '"'  -> parseString()
            't'  -> parseLiteral("true", true)
            'f'  -> parseLiteral("false", false)
            'n'  -> parseLiteral("null", null)
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        pos++ // '{'
        val result = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') { pos++; return result }
        while (true) {
            skipWhitespace()
            require(peek() == '"') { "Expected object key at $pos" }
            val key = parseString()
            skipWhitespace()
            require(peek() == ':') { "Expected ':' at $pos" }
            pos++
            result[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ','  -> pos++
                '}'  -> { pos++; return result }
                else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        pos++ // '['
        val result = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') { pos++; return result }
        while (true) {
            result.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ','  -> pos++
                ']'  -> { pos++; return result }
                else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
            }
        }
    }

    private fun parseString(): String {
        pos++ // opening '"'
        val sb = StringBuilder()
        while (true) {
            require(pos < text.length) { "Unterminated string" }
            when (val c = text[pos]) {
                '"'  -> { pos++; return sb.toString() }
                '\\' -> {
                    pos++
                    require(pos < text.length) { "Unterminated escape" }
                    when (val e = text[pos]) {
                        '"'  -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/'  -> sb.append('/')
                        'b'  -> sb.append('\b')
                        'f'  -> sb.append(0x0C.toChar())
                        'n'  -> sb.append('\n')
                        'r'  -> sb.append('\r')
                        't'  -> sb.append('\t')
                        'u'  -> {
                            require(pos + 4 < text.length) { "Truncated \\u escape" }
                            val hex = text.substring(pos + 1, pos + 5)
                            val code = hex.toIntOrNull(16)
                                ?: throw IllegalArgumentException("Bad \\u escape: $hex")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Bad escape '\\$e' at $pos")
                    }
                    pos++
                }
                else -> {
                    require(c.code >= 0x20) { "Unescaped control character at $pos" }
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        require(text.startsWith(literal, pos)) { "Invalid literal at $pos" }
        pos += literal.length
        return value
    }

    private fun parseNumber(): Any {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
        val token = text.substring(start, pos)
        require(token.isNotEmpty()) { "Expected value at $start" }
        // Integral numbers come back as Long (all backup fields are Long/Int);
        // anything fractional or exponential falls back to Double.
        return token.toLongOrNull()
            ?: token.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid number '$token' at $start")
    }

    private fun peek(): Char {
        require(pos < text.length) { "Unexpected end of JSON input" }
        return text[pos]
    }
}
