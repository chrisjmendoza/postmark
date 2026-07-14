package com.plusorminustwo.postmark.service.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.backup.isBackupFileName
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [CoroutineWorker] that writes the scheduled v2 backup archive — every thread
 * whose backupPolicy isn't NEVER_INCLUDE, all of time. The archive itself is
 * produced by [BackupArchiveExporter] (shared with the user-initiated
 * [ExportWorker]); this worker owns the destination (SAF folder or app dir),
 * retention pruning, and last-run status bookkeeping.
 *
 * Retries up to 3 times on failure, recording success or failure via
 * `SharedPreferences` so [BackupSettingsViewModel] can show the last run status.
 * Scheduled and cancelled by [BackupScheduler]; triggered on-demand by
 * [BackupSettingsViewModel.runNow].
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val threadRepository: ThreadRepository,
    private val exporter: BackupArchiveExporter
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val path = performBackup()
            val prefs = applicationContext.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("last_backup_timestamp", System.currentTimeMillis())
                .putString("last_backup_status", "SUCCESS")
                .putString("last_backup_path", path)
                .apply()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                val prefs = applicationContext.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("last_backup_timestamp", System.currentTimeMillis())
                    .putString("last_backup_status", "FAILED")
                    .apply()
                Result.failure()
            }
        }
    }

    private suspend fun performBackup(): String {
        val threads = threadRepository.getThreadsForBackup()

        val prefs = applicationContext.getSharedPreferences(BackupScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val filename = "postmark_${dateStamp()}.zip"

        // Preferred target: the user-chosen SAF folder (survives uninstall). Falls
        // back to the app dir if the folder or its permission has gone away, and
        // flags the fallback so the settings screen can surface it.
        val treeUriString = prefs.getString(BackupScheduler.KEY_TREE_URI, null)
        if (treeUriString != null) {
            try {
                val location = writeToTree(treeUriString, filename) { out ->
                    exporter.writeArchive(out, threads)
                }
                prefs.edit().putBoolean(BackupScheduler.KEY_TREE_FALLBACK, false).apply()
                pruneTree(treeUriString)
                return location
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                prefs.edit().putBoolean(BackupScheduler.KEY_TREE_FALLBACK, true).apply()
            }
        }

        val dir = applicationContext.getExternalFilesDir("backups")
            ?: throw IllegalStateException("External files dir unavailable")

        // Write to a .tmp name first so a crash mid-write never leaves a partial
        // file that isBackupFileName() would count as a real backup.
        val tempFile = File(dir, "$filename.tmp")
        val finalFile = File(dir, filename)
        try {
            FileOutputStream(tempFile).use { out ->
                exporter.writeArchive(out, threads)
            }
            if (!tempFile.renameTo(finalFile)) {
                throw IllegalStateException("Could not finalize ${finalFile.name}")
            }
        } finally {
            tempFile.delete() // no-op after a successful rename
        }

        // Prune only after the new backup is safely on disk — pruning first could
        // delete every existing backup and then fail the write, leaving zero backups.
        pruneOldBackups(dir)
        return finalFile.absolutePath
    }

    /** Creates [filename] in the SAF tree and streams the archive into it. Deletes
     *  the just-created document again if the write fails partway (a partial
     *  archive under a real backup name would look restorable). */
    private suspend fun writeToTree(
        treeUriString: String,
        filename: String,
        write: suspend (java.io.OutputStream) -> Unit
    ): String {
        val tree = DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUriString))
            ?: throw IllegalStateException("Backup folder unavailable")
        if (!tree.canWrite()) throw IllegalStateException("Backup folder is not writable")
        val doc = tree.createFile("application/zip", filename)
            ?: throw IllegalStateException("Could not create backup file in folder")
        try {
            val out = applicationContext.contentResolver.openOutputStream(doc.uri)
                ?: throw IllegalStateException("Could not open backup file for writing")
            write(out)
            return doc.uri.toString()
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
    }

    /** Retention pruning inside the SAF folder — same policy as the app dir. */
    private fun pruneTree(treeUriString: String) {
        val prefs = applicationContext.getSharedPreferences(BackupScheduler.PREFS_NAME, Context.MODE_PRIVATE)
        val retention = prefs.getInt("retention_count", 5)
        val tree = DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUriString)) ?: return
        val files = tree.listFiles().filter { isBackupFileName(it.name ?: "") }
        val doomed = selectBackupsToPrune(files.map { (it.name ?: "") to it.lastModified() }, retention)
        files.filter { it.name in doomed }.forEach { it.delete() }
    }

    private fun pruneOldBackups(dir: File) {
        val prefs = applicationContext.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
        val retention = prefs.getInt("retention_count", 5)
        // v1 .json files share the retention pool so they age out alongside v2 .zip.
        val files = dir.listFiles { f -> isBackupFileName(f.name) } ?: return
        val doomed = selectBackupsToPrune(files.map { it.name to it.lastModified() }, retention)
        files.filter { it.name in doomed }.forEach { it.delete() }
    }

    private fun dateStamp(): String =
        SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())

    companion object {
        const val WORK_NAME = "postmark_backup"
    }
}

/**
 * Pure retention policy: given (name, lastModified) pairs for every backup file —
 * including the one just written — returns the names to delete, keeping the
 * [retention] newest. Called after the new backup is on disk, never before: pruning
 * first could delete every existing backup and then fail the write.
 */
internal fun selectBackupsToPrune(files: List<Pair<String, Long>>, retention: Int): Set<String> =
    files.sortedByDescending { it.second }
        .drop(retention)
        .mapTo(mutableSetOf()) { it.first }
