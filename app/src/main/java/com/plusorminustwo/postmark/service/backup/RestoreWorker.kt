package com.plusorminustwo.postmark.service.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.plusorminustwo.postmark.PostmarkApplication
import com.plusorminustwo.postmark.data.db.PostmarkDatabase
import com.plusorminustwo.postmark.data.db.optimizeMessagesFts
import com.plusorminustwo.postmark.R
import com.plusorminustwo.postmark.data.contacts.lookupContactName
import com.plusorminustwo.postmark.ui.MainActivity
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.sync.SyncLogger
import com.plusorminustwo.postmark.domain.backup.BackupArchiveReader
import com.plusorminustwo.postmark.domain.backup.BackupFormat
import com.plusorminustwo.postmark.domain.backup.BackupLine
import com.plusorminustwo.postmark.domain.backup.MessageRecord
import com.plusorminustwo.postmark.domain.backup.RestoreMessageIndex
import com.plusorminustwo.postmark.domain.backup.ThreadRecord
import com.plusorminustwo.postmark.domain.backup.decodeBackupLine
import com.plusorminustwo.postmark.domain.backup.decodeBackupPolicy
import com.plusorminustwo.postmark.domain.backup.decodeManifest
import com.plusorminustwo.postmark.domain.backup.detectBackupFormat
import com.plusorminustwo.postmark.domain.backup.fallbackThreadId
import com.plusorminustwo.postmark.domain.backup.firstRestoredId
import com.plusorminustwo.postmark.domain.backup.mergeThreadMetadata
import com.plusorminustwo.postmark.domain.backup.messageFingerprint
import com.plusorminustwo.postmark.domain.backup.parseV1Backup
import com.plusorminustwo.postmark.domain.backup.sanitizeForRestore
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.model.previewText
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * [CoroutineWorker] that merges a backup file (v2 archive or legacy v1 JSON) into
 * the local database. Strictly additive:
 *
 * - Never deletes or overwrites anything — no `ContentResolver.delete`, no Room
 *   deletes of user data (the only file deletions are this run's own just-extracted
 *   blob files that turned out to be unreferenced).
 * - Messages are deduplicated by content fingerprint (see RestoreMerge.kt), so
 *   re-running a crashed or repeated restore is idempotent.
 * - Restored rows exist only in Room, with ids in the reserved RESTORED_ID_OFFSET
 *   range that the sync watermarks ignore. The system SMS/MMS providers are never
 *   written (except thread-id allocation via [Telephony.Threads.getOrCreateThreadId],
 *   which is what keeps a future incoming message from the same sender landing in
 *   the same thread).
 * - Thread metadata from the backup is applied only where the local thread still
 *   has default values; existing threads keep every user-made choice.
 */
@HiltWorker
class RestoreWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: PostmarkDatabase,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val syncLogger: SyncLogger
) : CoroutineWorker(context, params) {

    private class RestoreCounts {
        var threadsSeen = 0
        var threadsCreated = 0
        var inserted = 0
        var duplicates = 0
        var reactionsAdded = 0
        var linesSkipped = 0
        var attachmentsMissing = 0
    }

    /** Per-thread restore state, valid between a thread record and the next one. */
    private inner class ThreadContext(
        val threadId: Long,
        val localLastMessageAt: Long,
        val index: RestoreMessageIndex,
    ) {
        var newestInserted: Message? = null

        /** (messageId, senderAddress, emoji) of every reaction already present locally,
         *  plus those buffered by this run — one query per thread replaces the former
         *  per-reaction existence check. Filled lazily by [reactionKeysFor] on the first
         *  reaction record, since most threads have no reactions at all. */
        var reactionKeys: MutableSet<Triple<Long, String, String>>? = null
    }

    private suspend fun reactionKeysFor(ctx: ThreadContext): MutableSet<Triple<Long, String, String>> =
        ctx.reactionKeys
            ?: messageRepository.getReactionsByThread(ctx.threadId)
                .mapTo(mutableSetOf()) { Triple(it.messageId, it.senderAddress, it.emoji) }
                .also { ctx.reactionKeys = it }

    private var nextRestoredId = 0L
    private var progressDone = 0
    private var progressTotal = 0
    private val referencedShas = mutableSetOf<String>()
    private val extractedShas = mutableSetOf<String>()

    // Insert buffers, committed in one transaction per BATCH_SIZE rows and at every
    // thread boundary — a full restore is a few hundred commits instead of ~160k.
    private val pendingMessages = mutableListOf<Message>()
    private val pendingReactions = mutableListOf<Reaction>()

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        val source = inputData.getString(KEY_SOURCE)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No backup file specified"))
        syncLogger.log("Restore", "Restore started — attempt ${runAttemptCount + 1}, source=$source")
        return try {
            val counts = RestoreCounts()
            nextRestoredId = firstRestoredId(messageRepository.getMaxRestoredId())

            val format = openSource(source).use { stream ->
                val header = ByteArray(4)
                var read = 0
                while (read < header.size) {
                    val n = stream.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                detectBackupFormat(header.copyOf(read.coerceAtLeast(0)))
            }

            when (format) {
                BackupFormat.V2_ZIP -> restoreV2(source, counts)
                BackupFormat.V1_JSON -> restoreV1(source, counts)
                BackupFormat.UNKNOWN -> {
                    val msg = "Not a Postmark backup file"
                    syncLogger.logError("Restore", msg)
                    return Result.failure(workDataOf(KEY_ERROR to msg))
                }
            }

            cleanupUnreferencedBlobs()

            // Mass trigger-driven FTS inserts leave the index fragmented; runs after
            // the final flush, outside any transaction, and never fails the restore.
            db.optimizeMessagesFts()

            val status = buildString {
                append("Restored ${counts.inserted} messages")
                append(" (${counts.duplicates} already present)")
                if (counts.threadsCreated > 0) append(", ${counts.threadsCreated} new conversations")
                if (counts.reactionsAdded > 0) append(", ${counts.reactionsAdded} reactions")
                if (counts.linesSkipped > 0) append("; ${counts.linesSkipped} unreadable records skipped")
                if (counts.attachmentsMissing > 0) append("; ${counts.attachmentsMissing} attachments missing")
            }
            syncLogger.log("Restore", "Complete: $status")
            Result.success(workDataOf(KEY_STATUS to status))
        } catch (e: RestoreAbort) {
            // Deliberate permanent failures (wrong version, encrypted, unreadable) —
            // retrying can't help, and the message is user-facing.
            syncLogger.logError("Restore", e.message ?: "Restore aborted")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Restore failed")))
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            syncLogger.logError("Restore", "Exception during restore: $msg", e)
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to msg))
        }
    }

    // ------------------------------------------------------------------ v2 ----

    private suspend fun restoreV2(source: String, counts: RestoreCounts) {
        postProgress("Reading backup…", 0, 0)
        BackupArchiveReader(openSource(source)).use { reader ->
            var context: ThreadContext? = null
            while (true) {
                when (val event = reader.next() ?: break) {
                    is BackupArchiveReader.Event.Manifest -> {
                        val manifest = decodeManifest(event.json)
                            ?: throw RestoreAbort("Backup manifest is unreadable")
                        if (manifest.encryption != "none") {
                            throw RestoreAbort("This backup is encrypted, which this version of Postmark cannot read")
                        }
                        if (manifest.version > 2) {
                            throw RestoreAbort("This backup was created by a newer version of Postmark")
                        }
                        progressTotal = manifest.messageCount
                    }

                    is BackupArchiveReader.Event.Attachment ->
                        extractBlob(event.sha256, event.stream)

                    is BackupArchiveReader.Event.RecordLine -> {
                        val line = try {
                            decodeBackupLine(event.line)
                        } catch (_: IllegalArgumentException) {
                            counts.linesSkipped++
                            continue
                        }
                        when (line) {
                            is BackupLine.ThreadStart -> {
                                context?.let { finishThread(it) }
                                context = beginThread(line.thread, counts)
                            }
                            is BackupLine.Msg -> {
                                // A message record before any thread record means a
                                // corrupt stream — count it, don't guess a thread.
                                val ctx = context
                                if (ctx == null) counts.linesSkipped++
                                else restoreMessage(ctx, line.message, counts)
                            }
                            null -> {} // blank/unknown record type — forward compat
                        }
                    }
                }
            }
            context?.let { finishThread(it) }
        }
    }

    /** Extracts one blob to [blobFile] unless an identical one already exists.
     *  Writes via a temp name so a crash never leaves a truncated blob behind
     *  under its content-addressed (trusted) name. */
    private fun extractBlob(sha256: String, stream: InputStream) {
        if (!SHA256_HEX.matches(sha256)) return // corrupt/hostile entry name — skip
        val target = blobFile(sha256)
        if (target.exists()) return
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { out -> stream.copyTo(out) }
        if (!temp.renameTo(target)) temp.delete()
        extractedShas += sha256
    }

    // ------------------------------------------------------------------ v1 ----

    private suspend fun restoreV1(source: String, counts: RestoreCounts) {
        postProgress("Reading backup…", 0, 0)
        val text = openSource(source).use { it.readBytes().toString(Charsets.UTF_8) }
        val threads = try {
            parseV1Backup(text)
        } catch (e: IllegalArgumentException) {
            throw RestoreAbort("Backup file is unreadable: ${e.message}")
        }
        progressTotal = threads.sumOf { it.messages.size }
        for ((threadRecord, messages) in threads) {
            val ctx = beginThread(threadRecord, counts)
            for (message in messages) restoreMessage(ctx, message, counts)
            finishThread(ctx)
        }
    }

    // ------------------------------------------------------- merge pipeline ----

    /** Resolves the local thread for a backup thread record, creating it (with the
     *  backup's metadata) or merging metadata into an existing one, and builds the
     *  fingerprint index of its current messages. */
    private suspend fun beginThread(record: ThreadRecord, counts: RestoreCounts): ThreadContext {
        counts.threadsSeen++
        val threadId = resolveThreadId(record)
        val local = threadRepository.getById(threadId)

        if (local == null) {
            counts.threadsCreated++
            threadRepository.insertIgnore(
                Thread(
                    id = threadId,
                    // Prefer this device's contact name; group threads keep the
                    // backup's comma-joined roster name.
                    displayName = if (record.participants.size > 1) record.displayName
                    else applicationContext.lookupContactName(record.address) ?: record.displayName,
                    address = record.address,
                    lastMessageAt = 0L, // set by finishThread once messages land
                    lastMessagePreview = "",
                    backupPolicy = decodeBackupPolicy(record.backupPolicy),
                    isMuted = record.isMuted,
                    isPinned = record.isPinned,
                    notificationsEnabled = record.notificationsEnabled,
                    nickname = record.nickname,
                    participants = record.participants,
                    accentColorArgb = record.accentColorArgb,
                    chatBackgroundId = record.chatBackgroundId,
                    sentColorArgb = record.sentColorArgb,
                    isSpam = record.isSpam
                )
            )
        } else {
            val updates = mergeThreadMetadata(local, record)
            updates.nickname?.let { threadRepository.setNickname(threadId, it) }
            updates.isPinned?.let { threadRepository.updatePinned(threadId, it) }
            updates.isMuted?.let { threadRepository.updateMuted(threadId, it) }
            updates.notificationsEnabled?.let { threadRepository.updateNotificationsEnabled(threadId, it) }
            updates.backupPolicy?.let { threadRepository.updateBackupPolicy(threadId, it) }
            updates.accentColorArgb?.let { threadRepository.setAccentColor(threadId, it) }
            updates.chatBackgroundId?.let { threadRepository.setChatBackground(threadId, it) }
            updates.sentColorArgb?.let { threadRepository.setSentColor(threadId, it) }
            updates.isSpam?.let { threadRepository.updateSpam(threadId, it) }
        }

        val index = RestoreMessageIndex()
        for (message in messageRepository.getByThread(threadId)) {
            index.add(
                messageFingerprint(message.address, message.timestamp, message.isSent, message.isMms, message.body),
                message.id
            )
        }
        return ThreadContext(threadId, local?.lastMessageAt ?: 0L, index)
    }

    /** Real system thread id wherever possible — synthetic ids would collide with
     *  ids the provider hands out later and merge unrelated conversations. */
    private fun resolveThreadId(record: ThreadRecord): Long = try {
        if (record.participants.size > 1) {
            Telephony.Threads.getOrCreateThreadId(applicationContext, record.participants.toSet())
        } else {
            Telephony.Threads.getOrCreateThreadId(applicationContext, record.address)
        }
    } catch (e: Exception) {
        syncLogger.logError(
            "Restore",
            "getOrCreateThreadId failed for ${record.address} — using fallback id", e
        )
        fallbackThreadId(record.address)
    }

    private suspend fun restoreMessage(ctx: ThreadContext, raw: MessageRecord, counts: RestoreCounts) {
        val record = sanitizeForRestore(raw)
        record.attachments.forEach { referencedShas += it.sha256 }

        val fingerprint = messageFingerprint(
            record.address, record.timestamp, record.isSent, record.isMms, record.body
        )
        val matchedId = ctx.index.consumeMatch(fingerprint)
        val targetId: Long
        if (matchedId != null) {
            counts.duplicates++
            targetId = matchedId
        } else {
            val attachments = record.attachments.mapNotNull { ref ->
                val file = blobFile(ref.sha256)
                if (file.exists()) {
                    MessageAttachment(fileProviderUri(file), ref.mimeType)
                } else {
                    counts.attachmentsMissing++
                    null
                }
            }
            targetId = nextRestoredId++
            val message = Message(
                id = targetId,
                threadId = ctx.threadId,
                address = record.address,
                body = record.body,
                timestamp = record.timestamp,
                isSent = record.isSent,
                type = record.type,
                deliveryStatus = record.deliveryStatus,
                isMms = record.isMms,
                attachments = attachments,
                isRead = record.isRead,
                isStarred = record.isStarred,
                isPinned = record.isPinned
            )
            pendingMessages += message
            val newest = ctx.newestInserted
            if (newest == null || message.timestamp > newest.timestamp) {
                ctx.newestInserted = message
            }
            counts.inserted++
        }

        // Reactions merge onto both inserted and already-present messages.
        // Set.add doubles as the dedup check (against local rows and this run's buffer).
        if (record.reactions.isNotEmpty()) {
            val reactionKeys = reactionKeysFor(ctx)
            for (reaction in record.reactions) {
                if (reactionKeys.add(Triple(targetId, reaction.sender, reaction.emoji))) {
                    pendingReactions += Reaction(
                        id = 0, // auto-generated
                        messageId = targetId,
                        senderAddress = reaction.sender,
                        emoji = reaction.emoji,
                        timestamp = reaction.timestamp,
                        rawText = reaction.rawText
                    )
                    counts.reactionsAdded++
                }
            }
        }

        if (pendingMessages.size >= BATCH_SIZE || pendingReactions.size >= BATCH_SIZE) {
            flushBatch()
        }

        progressDone++
        if (progressDone % 200 == 0) {
            postProgress("Restoring messages", progressDone, progressTotal)
        }
    }

    /** Commits the buffered rows in one transaction. Messages go first so buffered
     *  reactions never violate the reactions→messages foreign key. */
    private suspend fun flushBatch() {
        if (pendingMessages.isEmpty() && pendingReactions.isEmpty()) return
        db.withTransaction {
            messageRepository.insertAll(pendingMessages)
            pendingReactions.forEach { messageRepository.insertReaction(it) }
        }
        pendingMessages.clear()
        pendingReactions.clear()
    }

    /** Flushes the thread's buffered rows, then rolls its list position/preview
     *  forward if restore added something newer than anything it had locally.
     *  Flushing first keeps the invariant that thread metadata never references
     *  a message that isn't committed yet. */
    private suspend fun finishThread(ctx: ThreadContext) {
        flushBatch()
        val newest = ctx.newestInserted ?: return
        if (newest.timestamp > ctx.localLastMessageAt) {
            threadRepository.updateLastMessageAt(ctx.threadId, newest.timestamp)
            threadRepository.updateLastMessagePreview(ctx.threadId, newest.previewText)
        }
    }

    /** Deletes blobs this run extracted that no message record ended up referencing
     *  (every referenced copy already existed locally). Only touches files created
     *  by this run — blobs referenced by earlier restores are never candidates,
     *  because their files already existed and were skipped at extraction. */
    private fun cleanupUnreferencedBlobs() {
        for (sha in extractedShas - referencedShas) {
            blobFile(sha).delete()
        }
    }

    // ------------------------------------------------------------- plumbing ----

    private fun blobFile(sha256: String): File =
        File(File(applicationContext.filesDir, BLOB_DIR), sha256)

    private fun fileProviderUri(file: File): String = FileProvider.getUriForFile(
        applicationContext, "${applicationContext.packageName}.fileprovider", file
    ).toString()

    /** Opens the backup source — a SAF/content URI (persisted read grant taken by
     *  the picker) or an absolute path inside the app's own backup directory. */
    private fun openSource(source: String): InputStream =
        if (source.startsWith("content:")) {
            applicationContext.contentResolver.openInputStream(Uri.parse(source))
                ?: throw RestoreAbort("Backup file could not be opened")
        } else {
            FileInputStream(File(source))
        }

    private class RestoreAbort(message: String) : Exception(message)

    // ---------------------------------------------------- foreground/progress ----

    private fun buildNotification(text: String, done: Int = 0, total: Int = 0): android.app.Notification {
        val openAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(applicationContext, PostmarkApplication.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Restoring backup")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildForegroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID_RESTORE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID_RESTORE, notification)
        }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(buildNotification("Restoring your messages…"))

    private suspend fun postProgress(phase: String, done: Int, total: Int) {
        val text = if (total > 0) "$phase — ${"%,d".format(done)} / ${"%,d".format(total)}" else phase
        setForeground(buildForegroundInfo(buildNotification(text, done, total)))
        setProgress(
            workDataOf(
                KEY_PROGRESS_PHASE to phase,
                KEY_PROGRESS_DONE to done,
                KEY_PROGRESS_TOTAL to total
            )
        )
    }

    companion object {
        const val WORK_NAME = "postmark_restore"
        const val KEY_SOURCE = "source"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_PHASE = "progress_phase"
        const val KEY_PROGRESS_DONE = "progress_done"
        const val KEY_PROGRESS_TOTAL = "progress_total"

        /** Directory under filesDir holding restored attachment blobs (covered by the
         *  FileProvider's whole-filesDir path, so the stored URIs are shareable). */
        const val BLOB_DIR = "restored_attachments"

        private const val NOTIF_ID_RESTORE = 1_002

        // Same batch size as SmsHistoryImportWorker's persist loop.
        private const val BATCH_SIZE = 500

        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
