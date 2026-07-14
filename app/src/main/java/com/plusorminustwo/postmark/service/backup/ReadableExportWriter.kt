package com.plusorminustwo.postmark.service.backup

import android.content.Context
import android.net.Uri
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.domain.backup.BackupSelection
import com.plusorminustwo.postmark.domain.backup.ReadableArchiveWriter
import com.plusorminustwo.postmark.domain.backup.extensionForMime
import com.plusorminustwo.postmark.domain.backup.mediaBaseName
import com.plusorminustwo.postmark.domain.backup.sanitizeForFileName
import com.plusorminustwo.postmark.domain.backup.uniqueFileName
import com.plusorminustwo.postmark.domain.backup.uniqueName
import com.plusorminustwo.postmark.domain.formatter.ExportFormatter
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import com.plusorminustwo.postmark.domain.model.Thread
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the readable ("text + media") export: one `ConversationName.txt` transcript
 * per thread — the same [ExportFormatter] format the Copy action produces, header
 * phone number included — and every attachment as a regular file under
 * `media/ConversationName/` with a date-stamped, extension-correct name. The point
 * is a zip a human opens and reads immediately; it is one-way by design (restore
 * needs the v2 archive from [BackupArchiveExporter], which keeps fingerprints and
 * content-addressed blobs precisely so it can merge without duplicates).
 *
 * Layout and naming rules are pure functions in `domain/backup/ReadableExport.kt`;
 * this class contributes only the queries and ContentResolver reads. Media whose
 * bytes can't be read (provider row vanished) is skipped: the transcript line still
 * names the file so the gap is visible rather than silent.
 */
@Singleton
class ReadableExportWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageRepository: MessageRepository
) {
    /**
     * Streams the readable zip for [threads] (already selection-filtered) narrowed
     * by [selection]'s date range to [out] (closed on return). Threads with no
     * in-range messages are skipped — an empty transcript is noise, not data.
     */
    suspend fun writeArchive(
        out: OutputStream,
        threads: List<Thread>,
        selection: BackupSelection = BackupSelection(),
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BackupArchiveExporter.ArchiveCounts {
        // Cheap DB-only pre-pass for a stable progress denominator (same two-query
        // pattern as BackupArchiveExporter — memory stays bounded by one thread).
        var totalMessages = 0
        for (thread in threads) {
            totalMessages += messageRepository.exportableMessagesFor(thread, selection).size
        }

        var threadCount = 0
        var messageCount = 0
        var attachmentCount = 0
        val takenBaseNames = mutableSetOf<String>()

        ReadableArchiveWriter(out).use { writer ->
            writer.writeText("README.txt", readmeText())

            for (thread in threads) {
                val messages = messageRepository.exportableMessagesFor(thread, selection)
                if (messages.isEmpty()) continue
                threadCount++

                val displayName = (thread.nickname ?: thread.displayName).ifBlank { thread.address }
                val base = uniqueName(sanitizeForFileName(displayName), takenBaseNames)

                val reactionsByMessage = messageRepository.getReactionsByThread(thread.id)
                    .groupBy { it.messageId }
                val withReactions = messages.map { msg ->
                    reactionsByMessage[msg.id]?.let { msg.copy(reactions = it) } ?: msg
                }

                // Assign every attachment its entry path up front so the transcript
                // can reference the exact file names.
                val takenMediaNames = mutableSetOf<String>()
                val mediaEntries = mutableListOf<Pair<String, String>>() // entryPath to uri
                val mediaByMessage = HashMap<Long, List<String>>()
                for (msg in withReactions) {
                    if (msg.attachments.isEmpty()) continue
                    mediaByMessage[msg.id] = msg.attachments.map { att ->
                        val file = uniqueFileName(
                            mediaBaseName(msg.timestamp),
                            extensionForMime(att.mimeType),
                            takenMediaNames
                        )
                        val entryPath = "media/$base/$file"
                        mediaEntries += entryPath to att.uri
                        entryPath
                    }
                }

                val transcript = ExportFormatter.formatForCopy(
                    messages = withReactions,
                    threadDisplayName = displayName,
                    ownAddress = SELF_ADDRESS,
                    threadAddress = thread.address
                ) { msg ->
                    mediaByMessage[msg.id]?.let { names ->
                        val label = if (names.size == 1) "Attachment" else "Attachments"
                        "[$label: ${names.joinToString(", ")}]"
                    }
                }
                writer.writeText("$base.txt", transcript)

                for ((entryPath, uri) in mediaEntries) {
                    val source = runCatching {
                        context.contentResolver.openInputStream(Uri.parse(uri))
                    }.getOrNull() ?: continue // bytes gone — transcript still names the file
                    writer.writeStream(entryPath, source)
                    attachmentCount++
                }

                messageCount += messages.size
                onProgress(messageCount, totalMessages)
            }
        }
        return BackupArchiveExporter.ArchiveCounts(threadCount, messageCount, attachmentCount)
    }

    private fun readmeText(): String {
        val stamp = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date())
        return """
            Postmark conversation export — $stamp

            Each conversation is one .txt file you can open anywhere; its photos,
            videos, and audio are under media/<conversation name>/ with the message
            date in each file name. Transcript lines like
            [Attachment: media/Sarah/2026-05-01_1432.jpg] point at those files.

            This is a readable snapshot, not a backup: Postmark cannot restore from
            it. For a restorable file, choose "Postmark backup" when exporting, or
            use the scheduled backups in Settings.
        """.trimIndent() + "\n"
    }
}
