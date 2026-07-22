package com.plusorminustwo.postmark.data.sync

import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.previewText
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Self-healing pass for MMS rows that imported with no readable content (empty body,
 * no attachments).
 *
 * How such rows happen: incoming MMS — including Google Messages' RCS archival rows,
 * which is how RCS reaction fallbacks reach us — are persisted by the platform, not by
 * Postmark. The text part can be file-backed (`text` column null, content on disk) or
 * the row can be observed before its parts finish writing. Either way the import saw
 * nothing, and because the incremental watermark only moves forward, the row froze as
 * an empty bubble forever (the ❤️-to-an-image case from the July 2026 reports).
 *
 * Each pass re-reads the provider parts for a bounded, newest-first set of empty rows
 * (via [MessageRepository.getEmptyMmsRows]) and, for every row that now yields content,
 * updates body/attachments and re-runs [ReactionResolver.resolveThread] on the affected
 * threads — so a recovered reaction fallback attaches as a Reaction immediately instead
 * of surfacing as a late text bubble. Still-empty rows are left untouched and retried
 * on the next pass.
 *
 * Called from [SmsSyncHandler.triggerCatchUp]. Room writes only — never touches the
 * telephony provider (CLAUDE.md CRITICAL).
 */
@Singleton
class EmptyMmsBodyRepair @Inject constructor(
    private val messageRepository: MessageRepository,
    private val threadRepository: ThreadRepository,
    private val reactionResolver: ReactionResolver
) {
    /** Rows whose content was recovered vs. rows that are still unreadable this pass. */
    data class Result(val repaired: Int, val stillEmpty: Int)

    /**
     * @param rereadParts Reads the provider parts for one raw MMS `_id`; null when the
     *                    parts cursor is unavailable (treated as "retry next pass").
     * @param log         Optional sink for per-row diagnostics.
     */
    internal suspend fun repair(
        rereadParts: suspend (rawMmsId: Long) -> MmsParsedResult?,
        log: (String) -> Unit = {}
    ): Result {
        val rows = messageRepository.getEmptyMmsRows(REPAIR_SCAN_LIMIT)
        if (rows.isEmpty()) return Result(0, 0)

        var repaired = 0
        var stillEmpty = 0
        val touchedThreads = mutableSetOf<Long>()
        for (row in rows) {
            val rawId = row.id - MMS_ID_OFFSET
            if (rawId <= 0) { stillEmpty++; continue }
            val parsed = rereadParts(rawId)
            if (parsed == null || (parsed.body.isEmpty() && parsed.attachments.isEmpty())) {
                stillEmpty++
                log("still empty: id=${row.id} rawId=$rawId")
                continue
            }
            if (parsed.body.isNotEmpty()) messageRepository.updateBody(row.id, parsed.body)
            if (parsed.attachments.isNotEmpty()) messageRepository.updateAttachments(row.id, parsed.attachments)
            touchedThreads += row.threadId
            repaired++
            log("repaired: id=${row.id} bodyLen=${parsed.body.length} attachments=${parsed.attachments.size}")
        }

        touchedThreads.forEach { threadId ->
            // A recovered body may be a reaction fallback — resolve it right away so it
            // attaches as a Reaction (and its row is removed) instead of popping up as
            // a brand-new text bubble mid-conversation.
            reactionResolver.resolveThread(threadId, log)
            // Refresh the preview in case the repaired row is the thread's latest
            // (idempotent when it isn't; resolveThread only repairs after deletions).
            messageRepository.getLatestForThread(threadId)?.let { latest ->
                threadRepository.updateLastMessageAt(threadId, latest.timestamp)
                threadRepository.updateLastMessagePreview(threadId, latest.previewText)
            }
        }
        return Result(repaired, stillEmpty)
    }

    companion object {
        /** Newest-first cap per pass — keeps a catch-up cheap even on a damaged DB. */
        internal const val REPAIR_SCAN_LIMIT = 25
    }
}
