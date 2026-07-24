package com.plusorminustwo.postmark.service.backup

import android.content.Context
import android.net.Uri
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.domain.backup.AttachmentRef
import com.plusorminustwo.postmark.domain.backup.BackupArchive
import com.plusorminustwo.postmark.domain.backup.BackupArchiveWriter
import com.plusorminustwo.postmark.domain.backup.BackupManifest
import com.plusorminustwo.postmark.domain.backup.BackupSelection
import com.plusorminustwo.postmark.domain.backup.MessageRecord
import com.plusorminustwo.postmark.domain.backup.ReactionRecord
import com.plusorminustwo.postmark.domain.backup.ThreadRecord
import com.plusorminustwo.postmark.domain.backup.encodeManifest
import com.plusorminustwo.postmark.domain.backup.encodeMessageLine
import com.plusorminustwo.postmark.domain.backup.encodeThreadLine
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.Thread
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a v2 backup archive for any [BackupSelection] — the shared engine behind
 * both the scheduled [BackupWorker] (all threads, all time) and user-initiated
 * [ExportWorker] slices (picked threads and/or a date range).
 *
 * Streaming layout and memory behavior are documented on [BackupArchive]; in
 * short, records are serialized one line at a time and attachment bytes are
 * copied stream-to-stream, so memory is bounded by one thread's message list.
 */
@Singleton
class BackupArchiveExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageRepository: MessageRepository
) {
    /** What ended up in the archive — drives worker status messages. */
    data class ArchiveCounts(
        val threadCount: Int,
        val messageCount: Int,
        val attachmentCount: Int
    )

    /**
     * Streams a complete archive covering [threads] (already policy/selection
     * filtered — see `selectThreadsForExport`) narrowed by [selection]'s date
     * range, to [out] (closed on return). When a date range is set, threads with
     * no in-range messages are skipped entirely; the whole-corpus backup keeps
     * empty threads so their metadata (nickname, pin, mute) survives.
     */
    suspend fun writeArchive(
        out: OutputStream,
        threads: List<Thread>,
        selection: BackupSelection = BackupSelection(),
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ArchiveCounts {
        // Pass A: walk every selected message once to hash attachment bytes (the
        // blob entry name must be known before the entry is written) and count
        // messages for the manifest. Each unique URI is read twice overall —
        // hash pass here, copy pass below — which keeps memory flat even for
        // large videos.
        val uriToSha = HashMap<String, String?>() // null = unreadable, backed up without bytes
        val shaToUri = LinkedHashMap<String, String>()
        var messageCount = 0
        var threadCount = 0
        for (thread in threads) {
            val messages = messagesFor(thread, selection)
            if (messages.isEmpty() && selection.hasDateRange) continue
            threadCount++
            for (message in messages) {
                messageCount++
                for (attachment in message.attachments) {
                    if (uriToSha.containsKey(attachment.uri)) continue
                    val sha = runCatching {
                        context.contentResolver.openInputStream(Uri.parse(attachment.uri))
                            ?.let { BackupArchive.sha256Hex(it) }
                    }.getOrNull()
                    uriToSha[attachment.uri] = sha
                    if (sha != null) shaToUri.putIfAbsent(sha, attachment.uri)
                }
            }
        }

        var done = 0
        BackupArchiveWriter(out).use { writer ->
            writer.writeManifest(
                encodeManifest(
                    BackupManifest(
                        version = 2,
                        exportedAt = System.currentTimeMillis(),
                        threadCount = threadCount,
                        messageCount = messageCount,
                        attachmentCount = shaToUri.size
                    )
                )
            )
            for ((sha, uri) in shaToUri) {
                val source = runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uri))
                }.getOrNull() ?: continue // vanished since the hash pass — skip blob
                writer.writeAttachment(sha, source)
            }
            for (thread in threads) {
                val messages = messagesFor(thread, selection)
                if (messages.isEmpty() && selection.hasDateRange) continue
                writer.writeRecordLine(encodeThreadLine(thread.toRecord()))
                val reactionsByMessage = messageRepository.getReactionsByThread(thread.id)
                    .groupBy { it.messageId }
                for (message in messages) {
                    writer.writeRecordLine(
                        encodeMessageLine(
                            message.toRecord(uriToSha, reactionsByMessage[message.id].orEmpty())
                        )
                    )
                    done++
                    if (done % 200 == 0) onProgress(done, messageCount)
                }
            }
        }
        return ArchiveCounts(threadCount, messageCount, shaToUri.size)
    }

    private suspend fun messagesFor(thread: Thread, selection: BackupSelection): List<Message> =
        messageRepository.exportableMessagesFor(thread, selection)
}

/** Messages [thread] contributes to an export: date-range narrowed when the
 *  selection has one, and never optimistic in-flight rows (negative ids aren't
 *  history yet). Shared by [BackupArchiveExporter] and [ReadableExportWriter]. */
internal suspend fun MessageRepository.exportableMessagesFor(
    thread: Thread,
    selection: BackupSelection
): List<Message> {
    val messages = if (selection.hasDateRange) {
        getByThreadAndDateRange(thread.id, selection.startMs, selection.endMs)
    } else {
        getByThread(thread.id)
    }
    return messages.filter { it.id >= 0 }
}

private fun Thread.toRecord() = ThreadRecord(
    address = address,
    displayName = displayName,
    nickname = nickname,
    isPinned = isPinned,
    isMuted = isMuted,
    notificationsEnabled = notificationsEnabled,
    backupPolicy = backupPolicy.name,
    participants = participants,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    accentColorArgb = accentColorArgb,
    chatBackgroundId = chatBackgroundId,
    sentColorArgb = sentColorArgb,
    isSpam = isSpam
)

private fun Message.toRecord(
    uriToSha: Map<String, String?>,
    reactions: List<Reaction>
) = MessageRecord(
    address = address,
    body = body,
    timestamp = timestamp,
    isSent = isSent,
    type = type,
    deliveryStatus = deliveryStatus,
    isMms = isMms,
    isRead = isRead,
    isStarred = isStarred,
    isPinned = isPinned,
    sentAt = sentAt,
    deliveredAt = deliveredAt,
    remindAt = remindAt,
    // An attachment whose bytes couldn't be read is dropped from the record rather
    // than pointing at a blob that isn't in the archive.
    attachments = attachments.mapNotNull { att ->
        uriToSha[att.uri]?.let { AttachmentRef(it, att.mimeType) }
    },
    reactions = reactions.map { ReactionRecord(it.senderAddress, it.emoji, it.timestamp, it.rawText) }
)
