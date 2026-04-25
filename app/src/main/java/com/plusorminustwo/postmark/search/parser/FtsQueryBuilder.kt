package com.plusorminustwo.postmark.search.parser

object FtsQueryBuilder {

    // Produces FTS5 word-start queries: ^"term"* matches words that start with "term"
    // but not words that merely contain it (e.g. "he" → "hello", NOT "the" or "when")
    fun build(rawInput: String): String {
        val term = rawInput.trim()
        if (term.isBlank()) return ""

        // Escape any FTS5 special characters inside the term
        val escaped = term.replace("\"", "\"\"")

        return "^\"$escaped\"*"
    }

    // For multi-word queries, each word becomes its own prefix phrase
    fun buildMultiWord(rawInput: String): String {
        val terms = rawInput.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return ""
        return terms.joinToString(" ") { term ->
            val escaped = term.replace("\"", "\"\"")
            "^\"$escaped\"*"
        }
    }
}
