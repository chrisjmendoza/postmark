package com.plusorminustwo.postmark.data.reaction

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
 * at first use so new verbs can be added without code changes. The internal
 * primary constructor accepts an arbitrary pattern provider so JVM unit tests
 * can construct the parser without an Android [Context].
 */
@Singleton
class AppleReactionParser internal constructor(
    patternsProvider: () -> List<ReactionPattern>
) {
    /** Production constructor — loads patterns lazily from the app's assets. */
    @Inject constructor(@ApplicationContext context: Context) : this({ loadPatterns(context) })

    private val patterns: List<ReactionPattern> by lazy(patternsProvider)

    // Matches: Loved 'some text' or Loved "some text"
    // Quote class covers: " " ' ' „ " « » (all common keyboard/locale variants)
    private val reactionRegex = Regex(
        """^(.+?)\s+[\u201C\u201D\u2018\u2019\u201E\u00AB\u00BB"'](.+?)[\u201C\u201D\u2018\u2019\u201E\u00AB\u00BB"']\s*$""",
        RegexOption.DOT_MATCHES_ALL
    )

    // iOS 17+ custom-emoji tapbacks (any emoji beyond the six named reactions above)
    // carry the emoji literally in the fallback verb rather than an English word:
    //   add:    Reacted 😎 to "text"
    //   remove: Removed a 😎 from "text"  /  Removed a 😎 reaction from "text"
    // There is no verb→emoji table to consult — the emoji IS the reaction, exactly
    // like the Android format. These match against the verb captured by reactionRegex.
    private val customTapbackAdd =
        Regex("""^Reacted\s+(.+?)\s+to$""", RegexOption.IGNORE_CASE)
    private val customTapbackRemove =
        Regex("""^Removed\s+(?:a|an)\s+(.+?)\s+(?:reaction\s+)?from$""", RegexOption.IGNORE_CASE)

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

        // Custom-emoji tapback: the emoji is carried literally in the verb (e.g. the
        // sunglasses 😎 in `Reacted 😎 to "…"`). The non-ASCII guard rejects real
        // sentences that happen to start "Reacted … to" — only an emoji qualifies.
        customTapbackAdd.find(verb)?.groupValues?.get(1)?.let { emoji ->
            if (emoji.isNotEmpty() && emoji[0].code > 127) {
                return ParsedReaction(emoji, quotedText, isRemoval = false)
            }
        }
        customTapbackRemove.find(verb)?.groupValues?.get(1)?.let { emoji ->
            if (emoji.isNotEmpty() && emoji[0].code > 127) {
                return ParsedReaction(emoji, quotedText, isRemoval = true)
            }
        }
        return null
    }

    internal data class ReactionPattern(
        val emoji: String,
        val verbs: List<String>,
        val removeVerbs: List<String>
    )

    private companion object {
        fun loadPatterns(context: Context): List<ReactionPattern> {
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
    }
}
