package com.plusorminustwo.postmark.search.parser

/**
 * Builds FTS4 MATCH query strings for use with [SearchDao].
 *
 * Queries use phrase-prefix matching — `"term*"`, star INSIDE the quotes — so a search
 * for "he" matches "hello" but not "the" or "where". FTS4 syntax differs from FTS5 here:
 * with the star outside the quotes (`"term"*`) FTS4 silently drops it and requires an
 * exact whole-word match, and a leading `^` anchors to the first token of the message
 * rather than to word starts.
 */
object FtsQueryBuilder {

    /**
     * Builds an FTS4 phrase-prefix query from [rawInput]. Multi-word input becomes a
     * single phrase with a prefix on the last word ("say hi" matches "say hive mind").
     *
     * Returns an empty string if [rawInput] is blank (caller should skip the FTS path).
     */
    fun build(rawInput: String): String {
        // FTS4 has no in-phrase quote escaping, but the simple tokenizer treats '"' as a
        // separator so quotes never appear in indexed tokens — replacing them with
        // spaces preserves match semantics while keeping the phrase syntax intact.
        val term = rawInput.replace('"', ' ').trim().replace(WHITESPACE, " ")
        if (term.isBlank()) return ""
        return "\"$term*\""
    }

    private val WHITESPACE = Regex("\\s+")
}
