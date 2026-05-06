package com.plusorminustwo.postmark.search.parser

/**
 * Builds FTS5 query strings for use with [SearchDao].
 *
 * Queries use word-start anchored prefix matching (`^"term"*`) so that a search for
 * "he" matches "hello" but not "the" or "where".
 */
object FtsQueryBuilder {

    /**
     * Builds a single-term FTS5 query from [rawInput].
     *
     * Returns an empty string if [rawInput] is blank (caller should skip the FTS path).
     */
    fun build(rawInput: String): String {
        val term = rawInput.trim()
        if (term.isBlank()) return ""

        // Escape any FTS5 special characters inside the term
        val escaped = term.replace("\"", "\"\"")

        return "^\"$escaped\"*"
    }

    /**
     * Builds a multi-term FTS5 query where each whitespace-separated word becomes
     * its own word-start prefix term. All terms must match (AND semantics).
     */
    fun buildMultiWord(rawInput: String): String {
        val terms = rawInput.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return ""
        return terms.joinToString(" ") { term ->
            val escaped = term.replace("\"", "\"\"")
            "^\"$escaped\"*"
        }
    }
}
