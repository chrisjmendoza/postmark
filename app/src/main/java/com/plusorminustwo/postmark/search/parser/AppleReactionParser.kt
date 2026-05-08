package com.plusorminustwo.postmark.search.parser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Represents a successfully parsed reaction fallback message.
 *
 * @property emoji The emoji character that was reacted.
 * @property quotedText The body of the message being reacted to (used for lookup).
 * @property isRemoval `true` when the reaction was removed (e.g. "Removed a heart from…").
 */
data class ParsedReaction(
    val emoji: String,
    val quotedText: String,
    val isRemoval: Boolean
)

/**
 * Parses Apple iMessage reaction fallback SMS messages.
 *
 * Apple format: `<Verb> "<quoted text>"` e.g. `Loved "Hello!"` or
 * `Removed a heart from "Hello!"`.
 *
 * Verb-to-emoji mappings are loaded from `assets/apple_reaction_patterns.json`
 * at first use so new verbs can be added without code changes.
 */
@Singleton
class AppleReactionParser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val patterns: List<ReactionPattern> by lazy { loadPatterns() }

    // Matches: Loved 'some text' or Loved "some text"
    // Quote class covers: " " ' ' „ " « » (all common keyboard/locale variants)
    private val reactionRegex = Regex(
        """^(.+?)\s+[\u201C\u201D\u2018\u2019\u201E\u00AB\u00BB"'](.+?)[\u201C\u201D\u2018\u2019\u201E\u00AB\u00BB"']\s*$""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * Attempts to parse [messageBody] as an Apple reaction fallback SMS.
     *
     * @return A [ParsedReaction] if the body matches and the verb is recognised, or `null`.
     */
    fun parse(messageBody: String): ParsedReaction? {
        val trimmed = messageBody.trim()
        val match = reactionRegex.find(trimmed) ?: return null
        val verb = match.groupValues[1].trim()
        val quotedText = match.groupValues[2].trim()

        patterns.forEach { pattern ->
            if (pattern.verbs.any { it.equals(verb, ignoreCase = true) }) {
                return ParsedReaction(pattern.emoji, quotedText, isRemoval = false)
            }
            if (pattern.removeVerbs.any { verb.startsWith(it, ignoreCase = true) }) {
                return ParsedReaction(pattern.emoji, quotedText, isRemoval = true)
            }
        }
        return null
    }

    private fun loadPatterns(): List<ReactionPattern> {
        val json = context.assets.open("apple_reaction_patterns.json")
            .bufferedReader()
            .readText()
        val root = JSONObject(json)
        val array: JSONArray = root.getJSONArray("patterns")
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            ReactionPattern(
                emoji = obj.getString("emoji"),
                verbs = obj.getJSONArray("verbs").let { a ->
                    (0 until a.length()).map { a.getString(it) }
                },
                removeVerbs = obj.getJSONArray("removeVerbs").let { a ->
                    (0 until a.length()).map { a.getString(it) }
                }
            )
        }
    }

    private data class ReactionPattern(
        val emoji: String,
        val verbs: List<String>,
        val removeVerbs: List<String>
    )
}
