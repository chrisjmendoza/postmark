package com.plusorminustwo.postmark.domain.storage

/**
 * One row in the Storage usage screen's per-conversation breakdown list.
 *
 * @param attachmentBytes app-local `mms_attach_*.bin`/`voice_memo_*.m4a` cache bytes
 *  attributed to this thread — NOT the size of received MMS media, which lives in the
 *  OS's own content provider and is never counted here.
 */
data class ConversationStorageRow(
    val threadId: Long,
    val name: String,
    val messageCount: Int,
    val attachmentBytes: Long
)

/**
 * Attributes filesDir attachment/voice-memo cache bytes to the thread that references them.
 *
 * [files] is (fileName, sizeBytes) for every `mms_attach_*.bin`/`voice_memo_*.m4a` file
 * actually present in filesDir. [uriToThread] maps a cache file name to the threadId of the
 * live message whose attachment URI's last path segment is that name (built from
 * `MessageRepository.getMessagesWithAttachments()`).
 *
 * A file with no entry in [uriToThread] contributes to no thread's total — this is
 * correct, not a bug: it's either a genuinely orphaned leftover (about to be swept) or a
 * voice memo recorded but not yet sent (still sitting in the reply bar's pending queue,
 * protected by its own grace period elsewhere), neither of which belongs to any thread yet.
 */
fun attributeAttachmentBytes(
    files: List<Pair<String, Long>>,
    uriToThread: Map<String, Long>
): Map<Long, Long> {
    val totals = mutableMapOf<Long, Long>()
    files.forEach { (name, bytes) ->
        val threadId = uriToThread[name] ?: return@forEach
        totals[threadId] = (totals[threadId] ?: 0L) + bytes
    }
    return totals
}

/**
 * Assembles the top-[limit] conversations by message count for the storage-usage
 * breakdown list, richest (most messages) first. Ties are broken by ascending threadId
 * so the ordering is deterministic and stable across recompositions/re-runs.
 *
 * A threadId present in [counts] but missing from [names] (e.g. a thread deleted between
 * the two queries) falls back to "Unknown" rather than dropping the row — the count is
 * still real storage.
 */
fun buildConversationBreakdown(
    counts: Map<Long, Int>,
    names: Map<Long, String>,
    attachmentBytesByThread: Map<Long, Long>,
    limit: Int = 20
): List<ConversationStorageRow> =
    counts.entries
        .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { (threadId, count) ->
            ConversationStorageRow(
                threadId = threadId,
                name = names[threadId] ?: "Unknown",
                messageCount = count,
                attachmentBytes = attachmentBytesByThread[threadId] ?: 0L
            )
        }
