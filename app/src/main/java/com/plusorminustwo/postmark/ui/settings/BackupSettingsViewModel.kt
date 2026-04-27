package com.plusorminustwo.postmark.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.plusorminustwo.postmark.service.backup.BackupScheduler
import com.plusorminustwo.postmark.service.backup.BackupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupScheduler: BackupScheduler,
    private val workManager: WorkManager
) : ViewModel() {

    private val _backupFiles = MutableStateFlow<List<BackupFileInfo>>(emptyList())
    val backupFiles: StateFlow<List<BackupFileInfo>> = _backupFiles.asStateFlow()

    val backupStatus: StateFlow<BackupStatus> = workManager
        .getWorkInfosForUniqueWorkFlow(BackupWorker.WORK_NAME)
        .map { workInfos ->
            val prefs = context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            val lastTimestamp = prefs.getLong("last_backup_timestamp", 0L)
            val state = workInfos.firstOrNull()?.state
            mapWorkInfoToStatus(state, lastTimestamp)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupStatus.Idle)

    init {
        refreshBackupFiles()
    }

    fun refreshBackupFiles() {
        viewModelScope.launch {
            _backupFiles.value = loadBackupFiles()
        }
    }

    fun deleteBackupFile(name: String) {
        viewModelScope.launch {
            val dir = context.getExternalFilesDir("backups") ?: return@launch
            File(dir, name).delete()
            _backupFiles.value = loadBackupFiles()
        }
    }

    fun deleteAllBackupFiles() {
        viewModelScope.launch {
            val dir = context.getExternalFilesDir("backups") ?: return@launch
            dir.listFiles()?.forEach { it.delete() }
            _backupFiles.value = emptyList()
        }
    }

    fun runNow() {
        backupScheduler.runNow()
    }

    private fun loadBackupFiles(): List<BackupFileInfo> {
        val dir = context.getExternalFilesDir("backups") ?: return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { file ->
                BackupFileInfo(
                    name = file.name,
                    sizeKb = maxOf(1L, file.length() / 1024),
                    modifiedAt = file.lastModified()
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

