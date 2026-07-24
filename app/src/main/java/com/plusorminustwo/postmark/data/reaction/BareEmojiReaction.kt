package com.plusorminustwo.postmark.data.reaction

import com.plusorminustwo.postmark.domain.model.Message
import java.text.BreakIterator

/*
 * Bare-emoji MMS reaction resolution — pure detection & target-finding.
 *
 * Device evidence (owner's phone, July 23 2026): a reaction to an IMAGE message over RCS
 * archives into the telephony MMS store as a message whose ENTIRE body is the bare emoji —
 * just `❤️`, with NO `❤️ to "quote"` structure — even when the image carried a caption.
 * Observed on a SENT reaction (owner reacted from the RCS side; archived as a sent MMS
 * bubble at the same minute); inbound media reactions are predicted to look identical
 * (docs/fable-reaction-parsing.md, "What this does NOT cover"). Every existing parser
 * strategy needs a quoted fallback, so these lone emoji render as stray bubbles forever.
 *
 * This file is intentionally pure (no DI, no Android deps beyond java.text.BreakIterator)
 * so the detection and attachment rules are table-testable off-device. The sync path
 * (SmsSyncHandler.syncLatestMms) and the historical heal (ReactionResolver.resolveThread)
 * both drive these functions so their behaviour can't drift apart.
 *
 * Removal variants are OUT OF SCOPE: there's no device evidence yet for what the archival
 * removal of a bare-emoji reaction looks like. TODO(bare-emoji-removal): capture the removal
 * PDU on-device and extend detection once its shape is known.
 */

/**
 * True when [text] trims to exactly ONE emoji grapheme cluster.
 *
 * "One grapheme" — not one char and not one code point — is the whole point: `❤️` is a
 * heart plus a U+FE0F variation selector, a skin-toned `👍🏽` is a base plus a Fitzpatrick
 * modifier, and `👨‍👩‍👧‍👦` is four people joined by zero-width joiners. All must count as a
 * single user-perceived character. [BreakIterator.getCharacterInstance] walks extended
 * grapheme-cluster boundaries (UAX #29), which collapses every one of those to a single
 * cluster. The first-code-point > 127 guard rejects plain ASCII up front so a typed `k`
 * or a lone digit can never be mistaken for an emoji reaction.
 *
 * Any extra text disqualifies: `❤️❤️`, `ok 👍`, and `👍!` all break into two-or-more
 * clusters and return false. Empty/blank returns false.
 */
fun isSingleEmojiGrapheme(text: String): Boolean {
    val s = text.trim()
    if (s.isEmpty()) return false
    // First code point must be non-ASCII — a plain letter/digit/punctuation is never an emoji.
    if (s.codePointAt(0) <= 127) return false

    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    var count = 0
    it.first()
    while (it.next() != BreakIterator.DONE) {
        count++
        if (count > 1) return false
    }
    return count == 1
}

/**
 * True when [message] is a BARE-EMOJI REACTION CANDIDATE: an MMS whose entire body is a
 * single emoji grapheme, carrying no attachments, in a 1:1 thread.
 *
 *  - MMS only. A typed emoji SMS (`isMms == false`) is a genuine message and must NEVER be
 *    converted to a reaction — the archival-as-MMS shape is the whole signal.
 *  - No attachments: a real media message with an emoji caption is content, not a reaction.
 *  - 1:1 only ([participantCount] <= 1; a 1:1 thread stores an empty roster, so 0 and 1 both
 *    qualify): in a group the "reply vs. reaction" ambiguity of a lone emoji is too high.
 *
 * The preceding-media restriction that actually attaches the reaction lives in
 * [findBareEmojiReactionTarget]; this predicate only decides that a row is shaped like a
 * bare-emoji reaction (so it joins the fallback partition instead of being inserted as a
 * bubble on sight).
 */
fun isBareEmojiReactionCandidate(message: Message, participantCount: Int): Boolean =
    message.isMms &&
        message.attachments.isEmpty() &&
        participantCount <= 1 &&
        isSingleEmojiGrapheme(message.body)

/**
 * Resolves the message a bare-emoji reaction [candidate] attaches to, or null when it
 * should stay a normal bubble.
 *
 * The candidate attaches to the message immediately preceding it by timestamp — but ONLY
 * if that predecessor is a media message (has at least one attachment). This media-
 * predecessor rule is the entire false-positive guard, deliberately chosen over a time
 * window: a lone `❤️` arriving right after a photo is a reaction; the same `❤️` arriving
 * right after a plain-text message is far more likely a genuine one-word reply ("real"
 * reactions to text arrive in the quoted `❤️ to "…"` form and take the existing path). So
 * if the immediate predecessor is text — or nothing precedes — this returns null and the
 * caller keeps the emoji as a visible bubble.
 *
 * The predecessor search excludes other reaction rows (quoted fallbacks via [isQuotedFallback]
 * and other bare-emoji candidates) so that, e.g., two hearts fired at one photo both resolve
 * to the photo rather than the second heart latching onto the first. The candidate is also
 * excluded from its own pool by id.
 */
fun findBareEmojiReactionTarget(
    candidate: Message,
    threadMessages: List<Message>,
    participantCount: Int,
    isQuotedFallback: (Message) -> Boolean
): Message? {
    val predecessor = threadMessages
        .asSequence()
        .filter { it.id != candidate.id }
        .filterNot { isQuotedFallback(it) }
        .filterNot { isBareEmojiReactionCandidate(it, participantCount) }
        .filter { it.timestamp <= candidate.timestamp }
        .maxByOrNull { it.timestamp }
        ?: return null

    // Attach only when the immediately-preceding real message is media; a text
    // predecessor means this bare emoji is a reply, not a reaction.
    return predecessor.takeIf { it.attachments.isNotEmpty() }
}
