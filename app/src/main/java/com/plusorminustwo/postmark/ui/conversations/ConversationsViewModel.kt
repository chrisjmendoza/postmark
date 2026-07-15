package com.plusorminustwo.postmark.ui.conversations

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.util.isDefaultSmsApp
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.sync.SmsHistoryImportWorker
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.service.sms.MmsManagerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Conversations (thread list) screen.
 *
 * Responsibilities:
 *  - Exposes the full ordered [threads] list and per-thread [unreadCounts] as reactive
 *    [StateFlow]s for the UI.
 *  - Monitors [SmsHistoryImportWorker] state to surface the [isSyncing] banner.
 *  - Performs sync recovery on startup: if threads exist but messages are missing (or
 *    vice-versa), it re-enqueues the first-launch sync worker to repair the database.
 */
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val smsSyncHandler: SmsSyncHandler,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val prefs get() = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    init {
        // If the app currently holds the default SMS role, clear any stale dismissal
        // so the banner can reappear if the role is lost in a future launch.
        if (context.isDefaultSmsApp()) prefs.edit().remove("role_banner_dismissed").apply()

        // Recovery: re-sync if:
        //   (a) sync marker is set but threads table is empty — original Samsung fallback case.
        //   (b) threads exist but the messages table is completely empty — this happens when the
        //       sync worker crashed between upsertAll(threads) and insertAll(messages), leaving
        //       threads with lastMessagePreviews but no actual Message rows in Room.
        viewModelScope.launch {
            val syncDone      = prefs.getBoolean("first_sync_completed", false)
            val threadsEmpty  = threadRepository.isEmpty()
            // EXISTS rather than the watermark queries: those exclude restored rows,
            // and a device holding only restored history must not loop recovery.
            val messagesEmpty = !messageRepository.hasAnyMessages()
            val needsRecovery = (syncDone && threadsEmpty) || (!threadsEmpty && messagesEmpty)
            if (needsRecovery) {
                android.util.Log.w(
                    "SyncTrigger",
                    "ConversationsViewModel.init: recovery — threadsEmpty=$threadsEmpty " +
                    "messagesEmpty=$messagesEmpty, enqueuing KEEP"
                )
                prefs.edit().remove("first_sync_completed").apply()
                workManager.enqueueUniqueWork(
                    SmsHistoryImportWorker.WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    SmsHistoryImportWorker.buildRequest()
                )
            }
        }

        // 60-second foreground polling: catch messages that arrived while the broadcast
        // receiver was paused or missed a delivery notification.
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                smsSyncHandler.triggerCatchUp()
            }
        }

        // One-shot sweep of orphaned outgoing-MMS cache files (mms_attach_*.bin) left by
        // superseded/failed sends. Files still referenced by a live message (each sent or
        // pending row points at its own via a FileProvider URI) are kept; a 1-hour age
        // guard inside the sweep protects a file an in-flight send just wrote.
        viewModelScope.launch(Dispatchers.IO) {
            val referenced = messageRepository.getMessagesWithAttachments()
                .flatMap { it.attachments }
                .mapNotNull { Uri.parse(it.uri).lastPathSegment }
                .toSet()
            MmsManagerWrapper.sweepOrphanedAttachmentCache(
                context, referenced, System.currentTimeMillis()
            )
        }
    }

    // Toggle for the "Unread only" filter chip in the top bar.
    private val _showUnreadOnly = MutableStateFlow(false)
    val showUnreadOnly: StateFlow<Boolean> = _showUnreadOnly.asStateFlow()

    // Live map of threadId → unread-message count, used by ThreadRow badges and the filter.
    val unreadCounts: StateFlow<Map<Long, Int>> = messageRepository.observeUnreadCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // All threads from Room — may be filtered below before reaching the UI.
    private val allThreads: StateFlow<List<Thread>?> = threadRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Derived: full list or unread-only list depending on the filter toggle.
    val threads: StateFlow<List<Thread>?> = combine(allThreads, unreadCounts, _showUnreadOnly) { list, counts, unreadOnly ->
        if (list == null) null
        else if (!unreadOnly) list
        else list.filter { (counts[it.id] ?: 0) > 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Flips the "show unread only" filter on or off. */
    fun toggleUnreadFilter() { _showUnreadOnly.update { !it } }

    // Single shared WorkDatabase observer feeding isSyncing / syncProgress / syncStatus.
    // Three independent getWorkInfosForUniqueWorkFlow calls each registered their own
    // observer, so every setProgress tick during a 100k-row import triple-queried the
    // Work DB and triple-mapped the result — precisely while the list was importing.
    private val importWorkInfos = workManager
        .getWorkInfosForUniqueWorkFlow(SmsHistoryImportWorker.WORK_NAME)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val isSyncing: StateFlow<Boolean> = importWorkInfos
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Live progress data emitted by the worker every 500 rows via setProgress().
    // Null when the worker is not running or hasn't emitted progress yet.
    val syncProgress: StateFlow<SyncProgress?> = importWorkInfos
        .map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: return@map null
            val data = running.progress
            val done = data.getInt(SmsHistoryImportWorker.KEY_PROGRESS_DONE, -1)
            if (done < 0) null
            else SyncProgress(
                phase = data.getString(SmsHistoryImportWorker.KEY_PROGRESS_PHASE) ?: "",
                done  = done,
                total = data.getInt(SmsHistoryImportWorker.KEY_PROGRESS_TOTAL, 0),
                eta   = data.getString(SmsHistoryImportWorker.KEY_PROGRESS_ETA) ?: ""
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Last known sync result — reads from SharedPreferences (written by the worker),
    // and updates live when a new work run completes.
    val syncStatus: StateFlow<String?> = importWorkInfos
        .map { infos ->
            val latest = infos.firstOrNull()
            when (latest?.state) {
                WorkInfo.State.SUCCEEDED ->
                    latest.outputData.getString(SmsHistoryImportWorker.KEY_STATUS)
                        ?: prefs.getString(SmsHistoryImportWorker.KEY_STATUS, null)
                WorkInfo.State.FAILED ->
                    latest.outputData.getString(SmsHistoryImportWorker.KEY_ERROR)
                        ?.let { "Error: $it" }
                        ?: prefs.getString(SmsHistoryImportWorker.KEY_STATUS, null)
                else -> prefs.getString(SmsHistoryImportWorker.KEY_STATUS, null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefs.getString(SmsHistoryImportWorker.KEY_STATUS, null))

    // ── Default SMS role ──────────────────────────────────────────────────────
    // Re-checked on every screen resume via refreshDefaultSmsStatus() so the banner
    // clears immediately when the user grants the role and returns to the app.
    private val _isDefaultSmsApp = MutableStateFlow(context.isDefaultSmsApp())
    val isDefaultSmsApp: StateFlow<Boolean> = _isDefaultSmsApp.asStateFlow()

    /** Called from ConversationsScreen's ON_RESUME lifecycle effect. */
    fun refreshDefaultSmsStatus() {
        _isDefaultSmsApp.value = context.isDefaultSmsApp()
    }

    private val _roleBannerDismissed = MutableStateFlow(
        prefs.getBoolean("role_banner_dismissed", false)
    )
    val roleBannerDismissed: StateFlow<Boolean> = _roleBannerDismissed.asStateFlow()

    fun dismissRoleBanner() {
        prefs.edit().putBoolean("role_banner_dismissed", true).apply()
        _roleBannerDismissed.value = true
    }

    fun triggerSync() {
        android.util.Log.i("SyncTrigger", "ConversationsViewModel.triggerSync — enqueuing REPLACE")
        prefs.edit().remove("first_sync_completed").apply()
        workManager.enqueueUniqueWork(
            SmsHistoryImportWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            SmsHistoryImportWorker.buildRequest()
        )
    }

    // ── Thread quick actions ─────────────────────────────────────────────────

    /** Flips the [isPinned] flag for [threadId]. Called from the conversation-list
     *  long-press context menu so the user can pin/unpin without opening the thread. */
    fun togglePin(threadId: Long, currentlyPinned: Boolean) {
        viewModelScope.launch {
            threadRepository.updatePinned(threadId, !currentlyPinned)
        }
    }

    /** Flips the [isMuted] flag for [threadId]. Called from the conversation-list
     *  long-press context menu so the user can mute/unmute without opening the thread. */
    fun toggleMute(threadId: Long, currentlyMuted: Boolean) {
        viewModelScope.launch {
            threadRepository.updateMuted(threadId, !currentlyMuted)
        }
    }

}

/** Snapshot of in-progress sync data emitted by [SmsHistoryImportWorker] via setProgress(). */
data class SyncProgress(
    val phase: String,
    val done: Int,
    val total: Int,
    val eta: String
)
