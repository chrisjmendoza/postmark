package com.plusorminustwo.postmark.service.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.plusorminustwo.postmark.PostmarkApplication
import com.plusorminustwo.postmark.R
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.sync.SyncLogger
import com.plusorminustwo.postmark.domain.backup.BackupSelection
import com.plusorminustwo.postmark.domain.backup.selectThreadsForExport
import com.plusorminustwo.postmark.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * [CoroutineWorker] that writes a user-initiated selective export — chosen
 * conversations and/or a date range — to a user-chosen SAF document
 * (CreateDocument), in one of two formats chosen on the Export screen:
 * [FORMAT_READABLE] (default) — human-facing text transcripts + media files via
 * [ReadableExportWriter]; or [FORMAT_BACKUP] — the same v2 archive as scheduled
 * backups via the shared [BackupArchiveExporter], restorable through the normal
 * restore flow.
 *
 * Unlike [BackupWorker] there is no retention pruning, no backup-prefs
 * bookkeeping, and no retry: the destination grant and the user's selection are
 * interactive context — on failure the partial document is deleted (the one file
 * this run created; a partial archive left behind would look restorable) and the
 * error is surfaced for the user to re-run.
 */
@HiltWorker
class ExportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val threadRepository: ThreadRepository,
    private val exporter: BackupArchiveExporter,
    private val readableWriter: ReadableExportWriter,
    private val syncLogger: SyncLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        val target = inputData.getString(KEY_TARGET_URI)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No destination file"))
        val pickedIds = inputData.getLongArray(KEY_THREAD_IDS)?.toSet() ?: emptySet()
        val selection = BackupSelection(
            threadIds = pickedIds.ifEmpty { null },
            startMs = inputData.getLong(KEY_START_MS, 0L),
            endMs = inputData.getLong(KEY_END_MS, Long.MAX_VALUE)
        )
        val format = inputData.getString(KEY_FORMAT) ?: FORMAT_READABLE
        syncLogger.log(
            "Export",
            "Export started — ${pickedIds.size.takeIf { it > 0 } ?: "all"} threads, " +
                "range=${selection.hasDateRange}, format=$format"
        )
        return try {
            val threads = selectThreadsForExport(threadRepository.observeAll().first(), selection)
            val uri = Uri.parse(target)
            val out = applicationContext.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open the destination file")
            val counts = if (format == FORMAT_BACKUP) {
                exporter.writeArchive(out, threads, selection) { done, total ->
                    postProgress("Exporting messages", done, total)
                }
            } else {
                readableWriter.writeArchive(out, threads, selection) { done, total ->
                    postProgress("Exporting messages", done, total)
                }
            }
            val status = "Exported ${counts.threadCount} conversations, " +
                "${counts.messageCount} messages" +
                if (counts.attachmentCount > 0) ", ${counts.attachmentCount} attachments" else ""
            syncLogger.log("Export", "Complete: $status")
            Result.success(workDataOf(KEY_STATUS to status))
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            syncLogger.logError("Export", "Export failed: $msg", e)
            deletePartialDocument(target)
            Result.failure(workDataOf(KEY_ERROR to msg))
        }
    }

    /** Removes the document this run was writing — CreateDocument made the (empty)
     *  file just before the worker started, so on failure the user is left with
     *  nothing rather than a half-written archive that looks restorable. */
    private fun deletePartialDocument(target: String) {
        try {
            DocumentsContract.deleteDocument(applicationContext.contentResolver, Uri.parse(target))
        } catch (_: Exception) {
            // Best effort — the provider may not support delete; leave the file.
        }
    }

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
            .setContentTitle("Exporting conversations")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun buildForegroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID_EXPORT, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID_EXPORT, notification)
        }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(buildNotification("Preparing export…"))

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
        const val WORK_NAME = "postmark_export"
        const val KEY_TARGET_URI = "target_uri"
        const val KEY_THREAD_IDS = "thread_ids"
        const val KEY_START_MS = "start_ms"
        const val KEY_END_MS = "end_ms"
        const val KEY_FORMAT = "format"
        const val FORMAT_READABLE = "readable"
        const val FORMAT_BACKUP = "backup"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_PHASE = "progress_phase"
        const val KEY_PROGRESS_DONE = "progress_done"
        const val KEY_PROGRESS_TOTAL = "progress_total"

        private const val NOTIF_ID_EXPORT = 1_003
    }
}
