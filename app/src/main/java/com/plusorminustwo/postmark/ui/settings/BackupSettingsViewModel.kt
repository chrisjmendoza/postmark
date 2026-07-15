package com.plusorminustwo.postmark.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.plusorminustwo.postmark.domain.backup.BackupArchiveReader
import com.plusorminustwo.postmark.domain.backup.BackupFormat
import com.plusorminustwo.postmark.domain.backup.BackupManifest
import com.plusorminustwo.postmark.domain.backup.decodeManifest
import com.plusorminustwo.postmark.domain.backup.detectBackupFormat
import com.plusorminustwo.postmark.service.backup.BackupScheduler
import com.plusorminustwo.postmark.service.backup.BackupWorker
import com.plusorminustwo.postmark.service.backup.RestoreWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * A backup file the user has picked for restore, awaiting confirmation.
 * [manifest] is present for v2 archives (drives the "contains N messages" copy);
 * null for legacy v1 files, which get generic dialog copy instead.
 */
data class PendingRestore(
    val source: String,
    val displayName: String,
    val manifest: BackupManifest?
)

/**
 * ViewModel for the Backup Settings screen.
 *
 * Observes [WorkManager] state to expose the current [BackupStatus] and
 * [RestoreStatus], manages the list of backup files in the app's external
 * `backups/` directory, the optional SAF backup folder, and the restore flow
 * (pick → confirm → enqueue [RestoreWorker]).
 *
 * Delegates scheduling to [BackupScheduler]; file I/O is performed on
 * [viewModelScope] so the UI never blocks on disk operations.
 */
@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupScheduler: BackupScheduler,
    private val workManager: WorkManager
) : ViewModel() {

    private val _backupFiles = MutableStateFlow<List<BackupFileInfo>>(emptyList())
    val backupFiles: StateFlow<List<BackupFileInfo>> = _backupFiles.asStateFlow()

    /** One-shot user feedback (Snackbar); clear with [clearFeedback]. */
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()
    fun clearFeedback() { _feedback.value = null }

    /** Backup file picked for restore, awaiting user confirmation. */
    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    /** Display name of the SAF backup folder, or null when using app storage. */
    private val _backupFolder = MutableStateFlow(currentFolderDisplayName())
    val backupFolder: StateFlow<String?> = _backupFolder.asStateFlow()

    val backupStatus: StateFlow<BackupStatus> = workManager
        .getWorkInfosForUniqueWorkFlow(BackupWorker.WORK_NAME)
        .map { workInfos ->
            val prefs = context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            val lastTimestamp = prefs.getLong("last_backup_timestamp", 0L)
            val state = workInfos.firstOrNull()?.state
            mapWorkInfoToStatus(state, lastTimestamp)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupStatus.Idle)

    val restoreStatus: StateFlow<RestoreStatus> = workManager
        .getWorkInfosForUniqueWorkFlow(RestoreWorker.WORK_NAME)
        .map { workInfos ->
            val info = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: workInfos.firstOrNull()
            mapRestoreStatus(
                state = info?.state,
                phase = info?.progress?.getString(RestoreWorker.KEY_PROGRESS_PHASE),
                done = info?.progress?.getInt(RestoreWorker.KEY_PROGRESS_DONE, 0) ?: 0,
                total = info?.progress?.getInt(RestoreWorker.KEY_PROGRESS_TOTAL, 0) ?: 0,
                statusMessage = info?.outputData?.getString(RestoreWorker.KEY_STATUS),
                errorMessage = info?.outputData?.getString(RestoreWorker.KEY_ERROR)
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RestoreStatus.None)

    init {
        refreshBackupFiles()
    }

    /** Rescans the external backups directory and updates [backupFiles]. */
    fun refreshBackupFiles() {
        viewModelScope.launch {
            _backupFiles.value = loadBackupFiles()
        }
    }

    /** Deletes the backup file with the given [name] and refreshes the list. */
    fun deleteBackupFile(name: String) {
        viewModelScope.launch {
            val dir = context.getExternalFilesDir("backups") ?: return@launch
            File(dir, name).delete()
            _backupFiles.value = loadBackupFiles()
        }
    }

    /** Deletes every file in the backups directory and clears [backupFiles]. */
    fun deleteAllBackupFiles() {
        viewModelScope.launch {
            val dir = context.getExternalFilesDir("backups") ?: return@launch
            dir.listFiles()?.forEach { it.delete() }
            _backupFiles.value = emptyList()
        }
    }

    /** Enqueues an immediate one-off backup via [BackupScheduler.runNow]. */
    fun runNow() {
        backupScheduler.runNow()
    }

    /** Re-syncs the periodic backup schedule with the persisted preferences.
     *  Call after any settings change that affects scheduling. */
    fun applySchedule() {
        backupScheduler.syncWithPrefs()
    }

    // ── Restore flow ─────────────────────────────────────────────────────────

    /** Validates a picked backup (SAF [uri]) and stages it for confirmation. */
    fun prepareRestoreFromUri(uri: Uri) {
        // Keep the grant alive until RestoreWorker (a separate process moment)
        // opens the file — a one-shot grant would not survive that far.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Not persistable (some providers) — the worker usually still gets the
            // transient grant; if not, it fails with a clear error.
        }
        prepareRestore(uri.toString(), uri.lastPathSegment ?: "backup")
    }

    /** Stages one of the app-dir backup files for restore confirmation. */
    fun prepareRestoreFromFile(file: BackupFileInfo) {
        prepareRestore(file.path, file.name)
    }

    private fun prepareRestore(source: String, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (readFormat(source)) {
                    BackupFormat.UNKNOWN ->
                        _feedback.value = "Not a Postmark backup file"
                    BackupFormat.V1_JSON ->
                        _pendingRestore.value = PendingRestore(source, displayName, manifest = null)
                    BackupFormat.V2_ZIP -> {
                        val manifest = BackupArchiveReader(openSource(source)).use { reader ->
                            (reader.next() as? BackupArchiveReader.Event.Manifest)
                                ?.let { decodeManifest(it.json) }
                        }
                        _pendingRestore.value = PendingRestore(source, displayName, manifest)
                    }
                }
            } catch (e: Exception) {
                _feedback.value = "Couldn't read backup: ${e.message}"
            }
        }
    }

    fun dismissPendingRestore() {
        _pendingRestore.value = null
    }

    /** Confirms the staged restore: hands the source to [RestoreWorker]. KEEP
     *  policy — a restore already in flight is never replaced mid-merge. */
    fun startRestore() {
        val pending = _pendingRestore.value ?: return
        _pendingRestore.value = null
        workManager.enqueueUniqueWork(
            RestoreWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RestoreWorker>()
                .setInputData(workDataOf(RestoreWorker.KEY_SOURCE to pending.source))
                .build()
        )
    }

    // ── SAF backup folder ────────────────────────────────────────────────────

    /** Persists a user-chosen SAF tree as the backup destination. */
    fun setBackupFolder(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            _feedback.value = "Couldn't keep access to that folder: ${e.message}"
            return
        }
        prefs().edit()
            .putString(BackupScheduler.KEY_TREE_URI, uri.toString())
            .putBoolean(BackupScheduler.KEY_TREE_FALLBACK, false)
            .apply()
        _backupFolder.value = currentFolderDisplayName()
    }

    /** Reverts to app-dir storage and releases the folder grant. */
    fun clearBackupFolder() {
        val prefs = prefs()
        prefs.getString(BackupScheduler.KEY_TREE_URI, null)?.let { stored ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(stored),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Grant already gone — nothing to release.
            }
        }
        prefs.edit()
            .remove(BackupScheduler.KEY_TREE_URI)
            .putBoolean(BackupScheduler.KEY_TREE_FALLBACK, false)
            .apply()
        _backupFolder.value = null
    }

    /** True when the last scheduled backup couldn't use the chosen folder. */
    fun folderFallbackHappened(): Boolean =
        prefs().getBoolean(BackupScheduler.KEY_TREE_FALLBACK, false)

    // ── Internals ────────────────────────────────────────────────────────────

    private fun prefs() =
        context.getSharedPreferences(BackupScheduler.PREFS_NAME, Context.MODE_PRIVATE)

    private fun currentFolderDisplayName(): String? =
        context.getSharedPreferences(BackupScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(BackupScheduler.KEY_TREE_URI, null)
            ?.let { Uri.parse(it).lastPathSegment ?: it }

    private fun readFormat(source: String): BackupFormat =
        openSource(source).use { stream ->
            val header = ByteArray(4)
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            detectBackupFormat(header.copyOf(read.coerceAtLeast(0)))
        }

    private fun openSource(source: String): InputStream =
        if (source.startsWith("content:")) {
            context.contentResolver.openInputStream(Uri.parse(source))
                ?: throw IllegalStateException("File could not be opened")
        } else {
            FileInputStream(File(source))
        }

    private fun loadBackupFiles(): List<BackupFileInfo> {
        val dir = context.getExternalFilesDir("backups") ?: return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { file ->
                BackupFileInfo(
                    name = file.name,
                    sizeKb = maxOf(1L, file.length() / 1024),
                    modifiedAt = file.lastModified(),
                    path = file.absolutePath
                )
            }
    }
}

internal val BACKUP_DATE_FORMATTER =
    SimpleDateFormat("MMM d yyyy HH:mm", Locale.getDefault()).also {
        it.timeZone = java.util.TimeZone.getDefault()
    }

internal fun formatBackupDate(timestamp: Long): String =
    BACKUP_DATE_FORMATTER.format(Date(timestamp))
