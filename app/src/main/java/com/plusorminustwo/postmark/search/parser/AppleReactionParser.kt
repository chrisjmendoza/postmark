package com.plusorminustwo.postmark.search.parser

import android.content.Context
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Reaction
import org.json.JSONArray
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ParsedReaction(
    val emoji: String,
    val quotedText: String,
    val isRemoval: Boolean
)

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

    // Finds the original message that a reaction refers to using:
    // 1. Exact match
    // 2. Prefix match (original starts with quoted text)
    // 3. Fuzzy containment
    fun findOriginalMessage(quotedText: String, candidates: List<Message>): Message? {
        val lower = quotedText.lowercase()
        return candidates.firstOrNull { it.body.equals(quotedText, ignoreCase = true) }
            ?: candidates.firstOrNull { it.body.startsWith(quotedText, ignoreCase = true) }
            ?: candidates.firstOrNull { it.body.lowercase().contains(lower) }
    }

    fun processIncomingMessage(
        message: Message,
        threadMessages: List<Message>,
        senderAddress: String
    ): Reaction? {
        val parsed = parse(message.body) ?: return null
        val original = findOriginalMessage(parsed.quotedText, threadMessages) ?: return null
        if (parsed.isRemoval) return null // caller handles deletion

        return Reaction(
            id = 0,
            messageId = original.id,
            senderAddress = senderAddress,
            emoji = parsed.emoji,
            timestamp = message.timestamp,
            rawText = message.body
        )
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
