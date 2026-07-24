package com.plusorminustwo.postmark.ui.conversations

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.plusorminustwo.postmark.data.preferences.GestureHintsRepository
import com.plusorminustwo.postmark.data.preferences.HomeBackgroundPreferenceRepository
import com.plusorminustwo.postmark.service.customization.ChatBackgroundImageStore
import com.plusorminustwo.postmark.data.repository.BlockedNumbersRepository
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.util.isDefaultSmsApp
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.sync.SmsHistoryImportWorker
import com.plusorminustwo.postmark.data.sync.SmsSyncHandler
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.selection.BlockCandidate
import com.plusorminustwo.postmark.domain.selection.blockResultMessage
import com.plusorminustwo.postmark.domain.selection.bulkToggleTarget
import com.plusorminustwo.postmark.domain.selection.partitionForBlock
import com.plusorminustwo.postmark.service.sms.MmsManagerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
import kotlinx.coroutines.withContext
import java.io.File
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
    private val gestureHintsRepo: GestureHintsRepository,
    private val homeBackgroundRepo: HomeBackgroundPreferenceRepository,
    private val backgroundImageStore: ChatBackgroundImageStore,
    private val blockedNumbersRepository: BlockedNumbersRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    /** Conversation-list background id; null = none (the plain theme background). Set from
     *  the Appearance screen; the same id vocabulary as the chat background. */
    val homeBackgroundId: StateFlow<String?> = homeBackgroundRepo.backgroundId

    /** Resolved file for a custom-image background id; null when the id isn't an image id
     *  or its file is gone (e.g. a backup restored onto a new device carries ids, not bytes). */
    fun homeBackgroundImageFile(id: String): File? = backgroundImageStore.fileFor(id)

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
        // receiver was paused or missed a delivery notification. Gated to the process
        // lifecycle — this VM lives on the nav back stack, so viewModelScope alone
        // would keep polling while the app is backgrounded.
        viewModelScope.launch {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Catch up immediately on every foreground entry: repeatOnLifecycle
                // restarts this block from zero each time, so a delay-first loop would
                // never fire for sessions shorter than 60 s — exactly the sessions in
                // which a message missed while backgrounded needs recovering. An idle
                // catch-up pass causes zero Room invalidations, so eager is cheap.
                while (true) {
                    smsSyncHandler.triggerCatchUp()
                    delay(60_000)
                }
            }
        }

        // One-shot sweep of orphaned outgoing-MMS cache files (mms_attach_*.bin, and
        // voice_memo_*.m4a leaked by a crash mid-recording) left by superseded/failed
        // sends. Files still referenced by a live message (each sent or pending row
        // points at its own via a FileProvider URI) are kept; a 1-hour age guard inside
        // the sweep protects a file an in-flight send just wrote (24 h for memos, which
        // can sit unsent in the reply bar's pending queue).
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
    // The filter itself is a pure function (see [filterThreadsByUnread]) so it can be
    // unit-tested — including that pinned-first ordering survives the filter.
    val threads: StateFlow<List<Thread>?> = combine(allThreads, unreadCounts, _showUnreadOnly) { list, counts, unreadOnly ->
        list?.let { filterThreadsByUnread(it, counts, unreadOnly) }
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

    // ── Multi-select ──────────────────────────────────────────────────────────
    // Set of selected thread ids. A non-empty set == selection mode is active.
    // The UI derives everything (selection-bar visibility, per-row check) from this
    // single flow — no parallel "isSelectionMode" boolean to keep in sync.
    private val _selectedThreadIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedThreadIds: StateFlow<Set<Long>> = _selectedThreadIds.asStateFlow()

    // One-shot result messages for the delete Snackbar. A buffered SharedFlow (not a
    // StateFlow) so a message shows once and doesn't re-fire on recomposition/re-collect.
    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages

    // One-time hint teaching the (invisible) long-press-to-multi-select gesture. True once
    // the user has dismissed it; the UI also gates on a non-empty, non-selecting list
    // (see [shouldShowMultiSelectHint]).
    val multiSelectHintDismissed: StateFlow<Boolean> = gestureHintsRepo.multiSelectHintDismissed

    /** Dismisses the multi-select hint row permanently. */
    fun dismissMultiSelectHint() = gestureHintsRepo.markMultiSelectHintDismissed()

    /** Long-press entry — enters selection mode with [threadId] selected. */
    fun enterSelection(threadId: Long) {
        _selectedThreadIds.update { it + threadId }
    }

    /** Tap while in selection mode — adds or removes [threadId] from the selection. */
    fun toggleThreadSelection(threadId: Long) {
        _selectedThreadIds.update { if (threadId in it) it - threadId else it + threadId }
    }

    /** Exits selection mode (X button, BackHandler, and after every bulk action). */
    fun clearSelection() {
        _selectedThreadIds.value = emptySet()
    }

    /** Selected threads resolved against the full (unfiltered) list. */
    private fun selectedThreads(): List<Thread> {
        val ids = _selectedThreadIds.value
        return allThreads.value?.filter { it.id in ids } ?: emptyList()
    }

    /** Pin/unpin every selected thread as a unit (see [bulkToggleTarget]). */
    fun pinSelected() {
        val targets = selectedThreads()
        val pin = bulkToggleTarget(targets.map { it.isPinned })
        viewModelScope.launch {
            targets.forEach { threadRepository.updatePinned(it.id, pin) }
            clearSelection()
        }
    }

    /** Mute/unmute every selected thread as a unit (see [bulkToggleTarget]). */
    fun muteSelected() {
        val targets = selectedThreads()
        val mute = bulkToggleTarget(targets.map { it.isMuted })
        viewModelScope.launch {
            targets.forEach { threadRepository.updateMuted(it.id, mute) }
            clearSelection()
        }
    }

    /** Marks every selected thread read (no-op-safe markAllRead per thread). */
    fun markSelectedRead() {
        val ids = _selectedThreadIds.value.toList()
        viewModelScope.launch {
            ids.forEach { messageRepository.markAllRead(it) }
            clearSelection()
        }
    }

    /** Marks every selected thread unread by clearing isRead on its latest message. */
    fun markSelectedUnread() {
        val ids = _selectedThreadIds.value.toList()
        viewModelScope.launch {
            ids.forEach { messageRepository.markLatestUnread(it) }
            clearSelection()
        }
    }

    /**
     * Marks every selected thread as spam — hides it into the Spam folder and silences its
     * notifications, same as the thread ⋮ menu's "Report as spam" (see
     * [com.plusorminustwo.postmark.ui.thread.ThreadViewModel.toggleSpam]). Always one-way:
     * [allThreads] is already filtered to non-spam threads ([ThreadDao.observeNonSpam]), so
     * the selection can never contain an already-spam thread to restore. Batches with
     * [ThreadRepository.markSpam] (a single `IN (:ids)` write) rather than looping
     * single-row updates. Callers MUST confirm with the user first — marking spam hides the
     * thread away, same as the single-thread action.
     */
    fun reportSpamSelected() {
        val ids = _selectedThreadIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            threadRepository.markSpam(ids)
            clearSelection()
            _snackbarMessages.tryEmit("Reported ${ids.size} as spam")
        }
    }

    /**
     * Blocks every selected NON-GROUP thread's address via the system
     * [android.provider.BlockedNumberContract] provider (recoverable from Settings ›
     * Privacy › Blocked numbers) — group threads are skipped since there's no single number
     * to block (see [partitionForBlock]), same reason "Block number" is hidden in the
     * thread ⋮ menu for group threads. Guarded by the default-SMS check like
     * [deleteSelected]; callers MUST confirm with the user first. Blocking does NOT delete
     * or spam-mark the conversations — this is a system-level block only, the threads
     * remain in the list until the user deletes them.
     */
    fun blockSelected() {
        if (!context.isDefaultSmsApp()) return
        val targets = selectedThreads()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val partition = partitionForBlock(
                targets.map { BlockCandidate(it.address, isGroup = it.participants.size > 1) }
            )
            val blockedCount = withContext(Dispatchers.IO) {
                partition.addressesToBlock.count { blockedNumbersRepository.block(it) }
            }
            clearSelection()
            _snackbarMessages.tryEmit(blockResultMessage(blockedCount, partition.skippedGroupCount))
        }
    }

    /**
     * Permanently deletes every selected conversation. Guarded by the default-SMS check
     * since non-default apps can't write the providers; callers MUST confirm with the user
     * first (the CLAUDE.md rule permits ContentResolver.delete() on telephony providers ONLY
     * as a direct result of an explicit user delete action). The actual delete work is
     * [deleteThreadsInternal] — see [deleteThread] for the single-conversation swipe path
     * that shares it.
     */
    fun deleteSelected() {
        if (!context.isDefaultSmsApp()) return
        val ids = _selectedThreadIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val (deleted, failed) = deleteThreadsInternal(ids)
            clearSelection()
            _snackbarMessages.tryEmit(deleteResultMessage(deleted, failed))
        }
    }

    /**
     * Swipe-to-delete entry point (ConversationsScreen's SwipeToDismissBox EndToStart
     * action) for a single conversation. Calls the exact same [deleteThreadsInternal] path
     * as [deleteSelected] with a one-element id list — there is only one delete
     * implementation, this just skips the selection-set bookkeeping bulk delete needs.
     * The caller (ConversationsScreen) is responsible for confirming with the user and for
     * the default-SMS-app gate before calling this; guarded here too as defense in depth.
     */
    fun deleteThread(threadId: Long) {
        if (!context.isDefaultSmsApp()) return
        viewModelScope.launch {
            val (deleted, failed) = deleteThreadsInternal(listOf(threadId))
            _snackbarMessages.tryEmit(deleteResultMessage(deleted, failed))
        }
    }

    /**
     * Deletes each thread in [ids]: on [Dispatchers.IO], the system telephony conversation
     * first, then the Room thread (its messages cascade-delete). A provider-delete failure
     * leaves that thread's Room row intact (a resync would just re-import it anyway) and
     * counts it as failed. Shared by [deleteSelected] (bulk) and [deleteThread] (swipe) so
     * there is exactly one delete implementation.
     */
    private suspend fun deleteThreadsInternal(ids: List<Long>): Pair<Int, Int> {
        var deleted = 0
        var failed = 0
        withContext(Dispatchers.IO) {
            ids.forEach { threadId ->
                val thread = threadRepository.getById(threadId) ?: return@forEach
                try {
                    val uri = ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId)
                    context.contentResolver.delete(uri, null, null)
                    threadRepository.delete(thread)
                    deleted++
                } catch (_: Exception) {
                    failed++
                }
            }
        }
        return deleted to failed
    }

    /**
     * Swipe-to-toggle-read entry point (ConversationsScreen's SwipeToDismissBox StartToEnd
     * action). Flips just [threadId]'s read state using the same per-thread repository
     * calls the multi-select Mark read / Mark unread actions use ([MessageRepository
     * .markAllRead] / [MessageRepository.markLatestUnread]) — no parallel implementation.
     * No confirmation needed; this isn't destructive.
     */
    fun toggleThreadRead(threadId: Long, markRead: Boolean) {
        viewModelScope.launch {
            if (markRead) messageRepository.markAllRead(threadId)
            else messageRepository.markLatestUnread(threadId)
            _snackbarMessages.tryEmit(if (markRead) "Marked read" else "Marked unread")
        }
    }

}

/**
 * Whether the long-press-to-multi-select hint row should show: only while the user hasn't
 * [dismissed] it, the list actually has conversations to select ([threadCount] > 0), and
 * selection mode isn't already active ([selectionActive] false). Extracted so the
 * visibility rule is testable without composing the UI.
 */
internal fun shouldShowMultiSelectHint(
    dismissed: Boolean,
    threadCount: Int,
    selectionActive: Boolean
): Boolean = !dismissed && threadCount > 0 && !selectionActive

/**
 * Number of threads that have at least one unread message. Derived from the same
 * threadId→unread-count map that drives the per-row badges, so the top-bar filter chip
 * count can never disagree with the badges (no parallel unread pipeline). A thread only
 * counts once no matter how many unread messages it holds.
 */
internal fun unreadThreadCount(unreadCounts: Map<Long, Int>): Int =
    unreadCounts.count { (_, count) -> count > 0 }

/**
 * Applies the "unread only" filter to the ordered thread list. When [showUnreadOnly] is
 * false the list is returned unchanged; when true, only threads whose [unreadCounts] entry
 * is > 0 survive. Relative order is preserved, so the caller's pinned-first ordering carries
 * through the filtered view untouched. Extracted as a pure function for testability.
 */
internal fun filterThreadsByUnread(
    threads: List<Thread>,
    unreadCounts: Map<Long, Int>,
    showUnreadOnly: Boolean
): List<Thread> =
    if (!showUnreadOnly) threads
    else threads.filter { (unreadCounts[it.id] ?: 0) > 0 }

/** Human-readable Snackbar text for a bulk-delete result. */
internal fun deleteResultMessage(deleted: Int, failed: Int): String = when {
    failed == 0 && deleted == 1 -> "1 conversation deleted"
    failed == 0                 -> "$deleted conversations deleted"
    deleted == 0                -> "Couldn't delete ${if (failed == 1) "conversation" else "$failed conversations"}"
    else                        -> "$deleted deleted, $failed failed"
}

/** Snapshot of in-progress sync data emitted by [SmsHistoryImportWorker] via setProgress(). */
data class SyncProgress(
    val phase: String,
    val done: Int,
    val total: Int,
    val eta: String
)
