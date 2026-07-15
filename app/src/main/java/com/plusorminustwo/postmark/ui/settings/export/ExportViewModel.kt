package com.plusorminustwo.postmark.ui.settings.export

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
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.backup.filterThreadsForExport
import com.plusorminustwo.postmark.domain.backup.localDateRangeToMillisBounds
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.service.backup.ExportWorker
import com.plusorminustwo.postmark.ui.settings.RestoreStatus
import com.plusorminustwo.postmark.ui.settings.mapRestoreStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Output format for a user-initiated export. */
enum class ExportFormat {
    /** One .txt transcript per conversation + media as regular files. One-way. */
    READABLE,

    /** The v2 backup archive — machine format, restorable/mergeable. */
    BACKUP
}

/**
 * ViewModel for the Export screen: multi-select over existing conversations, an
 * optional date range, a format choice, and a SAF destination handed to
 * [ExportWorker].
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    threadRepository: ThreadRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun onQueryChange(value: String) { _query.value = value }

    private val allThreads: StateFlow<List<Thread>> = threadRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Threads matching the search box (all of them for a blank query). */
    val visibleThreads: StateFlow<List<Thread>> =
        combine(allThreads, _query) { threads, query -> filterThreadsForExport(threads, query) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    fun toggleThread(threadId: Long) {
        _selectedIds.value = _selectedIds.value.let {
            if (threadId in it) it - threadId else it + threadId
        }
    }

    /** Selects or clears every currently *visible* thread — with a search active,
     *  "select all" means "all matches", which is what the user is looking at. */
    fun setAllVisible(selected: Boolean) {
        val visible = visibleThreads.value.map { it.id }
        _selectedIds.value =
            if (selected) _selectedIds.value + visible
            else _selectedIds.value - visible.toSet()
    }

    /** Readable is the default: "export" to most users means "files I can open". */
    private val _format = MutableStateFlow(ExportFormat.READABLE)
    val format: StateFlow<ExportFormat> = _format.asStateFlow()
    fun setFormat(value: ExportFormat) { _format.value = value }

    /** Picked date range as calendar days; null = all time. */
    private val _dateRange = MutableStateFlow<Pair<LocalDate, LocalDate>?>(null)
    val dateRange: StateFlow<Pair<LocalDate, LocalDate>?> = _dateRange.asStateFlow()
    fun setDateRange(start: LocalDate, end: LocalDate) { _dateRange.value = start to end }
    fun clearDateRange() { _dateRange.value = null }

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()
    fun clearFeedback() { _feedback.value = null }

    val exportStatus: StateFlow<RestoreStatus> = workManager
        .getWorkInfosForUniqueWorkFlow(ExportWorker.WORK_NAME)
        .map { workInfos ->
            val info = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: workInfos.firstOrNull()
            mapRestoreStatus(
                state = info?.state,
                phase = info?.progress?.getString(ExportWorker.KEY_PROGRESS_PHASE),
                done = info?.progress?.getInt(ExportWorker.KEY_PROGRESS_DONE, 0) ?: 0,
                total = info?.progress?.getInt(ExportWorker.KEY_PROGRESS_TOTAL, 0) ?: 0,
                statusMessage = info?.outputData?.getString(ExportWorker.KEY_STATUS),
                errorMessage = info?.outputData?.getString(ExportWorker.KEY_ERROR),
                runningFallback = "Exporting…",
                doneFallback = "Export complete",
                failedFallback = "Export failed",
                cancelledMessage = "Export cancelled"
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RestoreStatus.None)

    /** Suggested display name for the CreateDocument dialog. Both formats keep the
     *  `postmark_export_` prefix: isBackupFileName() excludes it, so retention
     *  pruning never deletes an export saved into the backup folder. */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
        return when (_format.value) {
            ExportFormat.READABLE -> "postmark_export_readable_$stamp.zip"
            ExportFormat.BACKUP -> "postmark_export_$stamp.zip"
        }
    }

    /** Hands the selection to [ExportWorker], writing to [target] (a CreateDocument
     *  result). KEEP policy — an export already in flight is never replaced. */
    fun startExport(target: Uri) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) {
            _feedback.value = "Select at least one conversation"
            return
        }
        // Keep the grant alive until the worker opens the document.
        try {
            context.contentResolver.takePersistableUriPermission(
                target,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Not persistable on this provider — the transient grant usually
            // suffices; if not, the worker fails with a clear error.
        }
        val bounds = _dateRange.value?.let { (start, end) ->
            localDateRangeToMillisBounds(start, end)
        }
        workManager.enqueueUniqueWork(
            ExportWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(
                    workDataOf(
                        ExportWorker.KEY_TARGET_URI to target.toString(),
                        ExportWorker.KEY_THREAD_IDS to ids.toLongArray(),
                        ExportWorker.KEY_START_MS to (bounds?.first ?: 0L),
                        ExportWorker.KEY_END_MS to (bounds?.second ?: Long.MAX_VALUE),
                        ExportWorker.KEY_FORMAT to when (_format.value) {
                            ExportFormat.READABLE -> ExportWorker.FORMAT_READABLE
                            ExportFormat.BACKUP -> ExportWorker.FORMAT_BACKUP
                        }
                    )
                )
                .build()
        )
    }
}
