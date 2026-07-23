package com.plusorminustwo.postmark.ui.settings

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.db.PostmarkDatabase
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.storage.ConversationStorageRow
import com.plusorminustwo.postmark.domain.storage.attributeAttachmentBytes
import com.plusorminustwo.postmark.domain.storage.buildConversationBreakdown
import com.plusorminustwo.postmark.service.backup.BackupScheduler
import com.plusorminustwo.postmark.service.sms.MmsManagerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val CONVERSATION_BREAKDOWN_LIMIT = 20

data class StorageUsageUiState(
    val loading: Boolean = true,
    val databaseBytes: Long = 0L,
    val attachmentBytes: Long = 0L,
    val attachmentCount: Int = 0,
    val chatBackgroundBytes: Long = 0L,
    val imageCacheBytes: Long = 0L,
    val backupBytes: Long = 0L,
    /** Bytes in the optional SAF backup folder, or null when none is configured. */
    val safBackupBytes: Long? = null,
    /** Best-effort display label for the SAF backup folder (its last path segment). */
    val safBackupLabel: String? = null,
    val syncLogBytes: Long = 0L,
    val conversations: List<ConversationStorageRow> = emptyList()
)

/**
 * ViewModel for [StorageUsageScreen]. Computes every section's on-disk size off the
 * main thread and exposes two safe cleanup actions.
 *
 * Sizes are a best-effort snapshot, not exact byte-for-byte disk usage: filesystem
 * block overhead isn't accounted for, and a size can shift slightly between the
 * measurement and the moment it's shown if e.g. a backup is running concurrently.
 * Good enough for "where did my storage go?", not a disk-usage auditor.
 */
@HiltViewModel
class StorageUsageViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messageRepository: MessageRepository,
    private val threadRepository: ThreadRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageUsageUiState())
    val uiState: StateFlow<StorageUsageUiState> = _uiState.asStateFlow()

    /** One-shot user feedback (Snackbar) after a cleanup action; clear with [clearSnackbar]. */
    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()
    fun clearSnackbar() { _snackbar.value = null }

    init { refresh() }

    /** Re-measures everything off the main thread. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(loading = true)
            _uiState.value = computeUsage()
        }
    }

    /**
     * Deletes filesDir attachment/voice-memo cache files no live message references.
     * Reuses [MmsManagerWrapper.sweepOrphanedAttachmentCache] with `minAgeMs = 0` for
     * mms_attach_ files — a genuinely orphaned one is safe to remove immediately, there's
     * no in-flight send to protect against. Voice memos keep their existing 24 h grace:
     * the sweep function internally takes `maxOf(minAgeMs, VOICE_MEMO_SWEEP_MIN_AGE_MS)`
     * for memo files, so a recorded-but-not-yet-sent memo is untouched regardless of the
     * minAgeMs passed here. Referenced files (anything a live message's attachment URI
     * points at) are never candidates in the first place.
     */
    fun cleanUpUnusedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val referenced = messageRepository.getMessagesWithAttachments()
                .flatMap { it.attachments }
                .mapNotNull { Uri.parse(it.uri).lastPathSegment }
                .toSet()
            val before = attachmentCacheBytes()
            val deletedCount = MmsManagerWrapper.sweepOrphanedAttachmentCache(
                context, referenced, System.currentTimeMillis(), minAgeMs = 0L
            )
            val freed = before - attachmentCacheBytes()
            _snackbar.value = if (deletedCount > 0) {
                "Freed ${Formatter.formatFileSize(context, freed)} " +
                    "($deletedCount unused file${if (deletedCount == 1) "" else "s"})"
            } else {
                "No unused files to clean up"
            }
            _uiState.value = computeUsage()
        }
    }

    /** Deletes Coil's default disk cache (`cacheDir/image_cache`) — safe, images
     *  re-fetch from their content URIs on next view. */
    fun clearImageCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(context.cacheDir, "image_cache")
            val freed = dirSize(dir)
            dir.deleteRecursively()
            _snackbar.value = "Freed ${Formatter.formatFileSize(context, freed)}"
            _uiState.value = computeUsage()
        }
    }

    private suspend fun computeUsage(): StorageUsageUiState {
        val dbFile = context.getDatabasePath(PostmarkDatabase.DATABASE_NAME)
        val databaseBytes = dbFile.length() +
            File("${dbFile.path}-wal").length() +
            File("${dbFile.path}-shm").length()

        val cacheFiles = context.filesDir
            .listFiles { f -> MmsManagerWrapper.isOutgoingCacheFileName(f.name) }
            ?.toList().orEmpty()

        val chatBackgroundBytes = dirSize(File(context.filesDir, "chat_backgrounds"))
        val imageCacheBytes = dirSize(File(context.cacheDir, "image_cache"))
        val backupBytes = dirSize(context.getExternalFilesDir("backups"))
        val syncLogFile = File(context.filesDir, "sync_log.txt")
        val syncLogBytes = if (syncLogFile.exists()) syncLogFile.length() else 0L

        val (safBytes, safLabel) = safBackupFolderUsage()

        // Per-conversation breakdown: message counts + thread names from Room, app-local
        // attachment bytes attributed via the same filename-matching the orphan sweep uses.
        val counts = messageRepository.getMessageCountsByThread()
        val names = threadRepository.getAll().associate { it.id to (it.nickname ?: it.displayName) }
        val uriToThread = mutableMapOf<String, Long>()
        messageRepository.getMessagesWithAttachments().forEach { message ->
            message.attachments.forEach { attachment ->
                Uri.parse(attachment.uri).lastPathSegment?.let { name ->
                    if (MmsManagerWrapper.isOutgoingCacheFileName(name)) uriToThread[name] = message.threadId
                }
            }
        }
        val attachmentBytesByThread = attributeAttachmentBytes(
            cacheFiles.map { it.name to it.length() },
            uriToThread
        )
        val breakdown = buildConversationBreakdown(
            counts, names, attachmentBytesByThread, limit = CONVERSATION_BREAKDOWN_LIMIT
        )

        return StorageUsageUiState(
            loading = false,
            databaseBytes = databaseBytes,
            attachmentBytes = cacheFiles.sumOf { it.length() },
            attachmentCount = cacheFiles.size,
            chatBackgroundBytes = chatBackgroundBytes,
            imageCacheBytes = imageCacheBytes,
            backupBytes = backupBytes,
            safBackupBytes = safBytes,
            safBackupLabel = safLabel,
            syncLogBytes = syncLogBytes,
            conversations = breakdown
        )
    }

    /** Total bytes of a configured SAF backup folder plus a cheap display label (its last
     *  path segment) — or (null, null) when no folder is configured or it's unreadable. */
    private fun safBackupFolderUsage(): Pair<Long?, String?> {
        val treeUriString = context
            .getSharedPreferences(BackupScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(BackupScheduler.KEY_TREE_URI, null) ?: return null to null
        return runCatching {
            val treeUri = Uri.parse(treeUriString)
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null to null
            val bytes = tree.listFiles().sumOf { it.length() }
            bytes to (treeUri.lastPathSegment ?: treeUriString)
        }.getOrDefault(null to null)
    }

    private fun attachmentCacheBytes(): Long =
        (context.filesDir.listFiles { f -> MmsManagerWrapper.isOutgoingCacheFileName(f.name) } ?: emptyArray())
            .sumOf { it.length() }

    private fun dirSize(dir: File?): Long =
        dir?.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L
}
