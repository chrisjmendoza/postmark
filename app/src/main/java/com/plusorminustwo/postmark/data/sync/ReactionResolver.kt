package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import com.plusorminustwo.postmark.domain.model.previewText
import com.plusorminustwo.postmark.data.reaction.ReactionFallbackParser
import com.plusorminustwo.postmark.data.reaction.findBareEmojiReactionTarget
import com.plusorminustwo.postmark.data.reaction.isBareEmojiReactionCandidate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves stored reaction fallback messages (`👍 to "…"` / `Liked "…"`) into Reaction
 * entities, deleting the fallback row once it has been resolved so it no longer shows
 * as a plain chat bubble.
 *
 * Single source of truth for full-history reaction resolution. Called by
 * [SmsHistoryImportWorker] exactly once, after BOTH the SMS and the MMS historical
 * imports have persisted — the quoted-text candidate pool must span both transports,
 * otherwise a reaction to an MMS-originated message (or a fallback that itself arrived
 * as MMS) can never match. Also called by DevOptionsViewModel.reprocessReactions()
 * as a manual repair tool, by [EmptyMmsBodyRepair] for threads whose recovered MMS
 * body turns out to be a reaction fallback, and once per install by
 * [SmsSyncHandler]'s reaction-reprocess pass (after the truncated-quote matching fix).
 *
 * Incremental sync ([SmsSyncHandler]) resolves reactions inline as messages arrive
 * and does not use this class for that path.
 */
@Singleton
class ReactionResolver @Inject constructor(
    private val messageRepository: MessageRepository,
    private val threadRepository: ThreadRepository,
    private val reactionParser: ReactionFallbackParser
) {
    /** Counts of reactions inserted and fallback bubbles removed by a resolution pass. */
    data class Result(val inserted: Int, val removed: Int)

    /**
     * Resolves reaction fallbacks across every thread in the database.
     *
     * @param log      Optional sink for per-event diagnostics (callers prepend their own tag).
     * @param onThread Invoked before each thread is processed with (index, total) — used by
     *                 DevOptions for progress labels and cooperative yielding.
     */
    suspend fun resolveAll(
        log: (String) -> Unit = {},
        onThread: suspend (index: Int, total: Int) -> Unit = { _, _ -> }
    ): Result {
        val threadIds = messageRepository.getAllThreadIds()
        log("${threadIds.size} threads to scan")
        var inserted = 0
        var removed = 0
        threadIds.forEachIndexed { index, threadId ->
            onThread(index, threadIds.size)
            val result = resolveThread(threadId, log)
            inserted += result.inserted
            removed += result.removed
        }
        return Result(inserted, removed)
    }

    /**
     * Resolves reaction fallbacks within a single thread against the thread's full
     * message set, then deletes resolved fallback rows and repairs the thread preview
     * if it pointed at one. Fallbacks whose original cannot be found are left in place
     * as normal visible bubbles.
     */
    suspend fun resolveThread(threadId: Long, log: (String) -> Unit = {}): Result {
        val msgs = messageRepository.getByThread(threadId)

        // A 1:1 thread stores an empty roster, so participantCount 0/1 both mean 1:1 —
        // the gate for bare-emoji candidacy. Resolved once per thread (cheap Room read).
        val participantCount = threadRepository.getById(threadId)?.participants?.size ?: 0

        // Pre-partition: reaction rows must never be candidates for matching. This spans
        // both quoted fallbacks and bare-emoji reaction candidates (lone-emoji media
        // reactions archived without any quoted structure — BareEmojiReaction.kt).
        val quotedFallbacks = msgs.filter { reactionParser.isReactionFallback(it.body) }
        val bareEmojiCandidates = msgs.filter {
            !reactionParser.isReactionFallback(it.body) && isBareEmojiReactionCandidate(it, participantCount)
        }
        val reactionMsgs = quotedFallbacks + bareEmojiCandidates
        if (reactionMsgs.isEmpty()) return Result(0, 0)
        val normalMsgs = msgs.filter {
            !reactionParser.isReactionFallback(it.body) && !isBareEmojiReactionCandidate(it, participantCount)
        }

        log("thread=$threadId: ${msgs.size} msgs, ${quotedFallbacks.size} quoted fallbacks, ${bareEmojiCandidates.size} bare-emoji candidates")

        var inserted = 0
        var removed = 0
        val toDelete = mutableListOf<Long>()
        reactionMsgs.forEach { msg ->
            val senderAddress = if (msg.isSent) SELF_ADDRESS else msg.address
            val parsed = reactionParser.parse(msg.body)

            if (parsed == null) {
                // ── Bare-emoji reaction candidate ──
                // Attach to the immediately-preceding media message; a sent candidate uses
                // SELF_ADDRESS so it dedupes against any existing local pill. When resolved
                // (or already recorded) the stray bubble is removed; when it can't attach
                // (no media predecessor) it's left in place as a normal bubble.
                val target = findBareEmojiReactionTarget(msg, normalMsgs, participantCount) {
                    reactionParser.isReactionFallback(it.body)
                }
                if (target != null) {
                    val emoji = msg.body.trim()
                    if (!messageRepository.reactionExists(target.id, senderAddress, emoji)) {
                        messageRepository.insertReaction(
                            Reaction(
                                id = 0,
                                messageId = target.id,
                                senderAddress = senderAddress,
                                emoji = emoji,
                                timestamp = msg.timestamp,
                                rawText = msg.body
                            )
                        )
                        inserted++
                        log("bare-emoji matched: id=${msg.id} emoji=$emoji → targetId=${target.id}")
                    } else {
                        log("bare-emoji duplicate: id=${msg.id} emoji=$emoji already exists — redundant row removed")
                    }
                    toDelete += msg.id
                    removed++
                } else {
                    log("bare-emoji no-match: id=${msg.id} emoji=${msg.body.trim()} — no media predecessor, left as bubble")
                }
                return@forEach
            }

            val reaction = reactionParser.processIncomingMessage(msg, normalMsgs, senderAddress)

            if (reaction != null) {
                if (parsed.isRemoval) {
                    // Remove the reaction entity and delete the fallback bubble.
                    messageRepository.deleteReaction(reaction.messageId, reaction.senderAddress, reaction.emoji)
                    toDelete += msg.id
                    removed++
                    log("removal: id=${msg.id} emoji=${parsed.emoji} deleted")
                } else if (!messageRepository.reactionExists(
                        reaction.messageId, reaction.senderAddress, reaction.emoji)) {
                    messageRepository.insertReaction(reaction)
                    inserted++
                    toDelete += msg.id
                    removed++
                    log("matched: id=${msg.id} emoji=${parsed.emoji} → targetId=${reaction.messageId}")
                } else {
                    // The reaction is already recorded (an earlier pass resolved another
                    // copy), so this raw fallback row is redundant — delete it like the
                    // resolved case instead of leaving a permanent stray bubble.
                    toDelete += msg.id
                    removed++
                    log("duplicate: id=${msg.id} emoji=${parsed.emoji} already exists — redundant row removed")
                }
            } else {
                // Original not found within the 100-message window — leave as visible bubble.
                log("no-match: id=${msg.id} emoji=${parsed.emoji} quoteLen=${parsed.quotedText.length}")
            }
        }

        if (toDelete.isNotEmpty()) {
            toDelete.forEach { messageRepository.deleteById(it) }
            // Fix the thread preview if the last message was a reaction fallback text.
            messageRepository.getLatestForThread(threadId)?.let { latest ->
                threadRepository.updateLastMessageAt(threadId, latest.timestamp)
                threadRepository.updateLastMessagePreview(threadId, latest.previewText)
            }
        }
        return Result(inserted, removed)
    }
}
