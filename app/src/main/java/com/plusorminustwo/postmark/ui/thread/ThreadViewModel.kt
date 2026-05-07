package com.plusorminustwo.postmark.ui.thread

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.core.content.FileProvider
import java.io.File
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.preferences.TimestampPreferenceRepository
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import com.plusorminustwo.postmark.service.sms.MmsManagerWrapper
import com.plusorminustwo.postmark.service.sms.MmsSentReceiver
import com.plusorminustwo.postmark.service.sms.SmsManagerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * All UI state for the thread screen, collected by [ThreadScreen] and rendered by [ThreadContent].
 *
 * @property thread                  The current [Thread] metadata (display name, muted, pinned, etc.).
 * @property messages                All messages in this thread, newest last (LazyColumn renders reversed).
 * @property selectedMessageIds      IDs of messages currently checked in multi-select mode.
 * @property isSelectionMode         True when the screen is in multi-select mode.
 * @property selectionScope          Scope of the current selection (MESSAGES or ALL).
 * @property replyText               Current text in the reply bar input field.
 * @property isSending               True while an outgoing message is being handed to telephony.
 * @property showDefaultSmsDialog    True when the "Set as default SMS app" dialog should be shown.
 * @property expandedTimestampIds    IDs of bubbles whose timestamps are currently revealed (ON_TAP mode).
 * @property reactionPickerMessageId ID of the message whose action bar / emoji picker is open, or null.
 * @property reactionPickerBubbleY   Root-Y of the long-pressed bubble, used to position the emoji popup.
 * @property highlightedMessageId    ID of the message highlighted after a search-jump; clears after 2 s.
 */
data class ThreadUiState(
    val thread: Thread? = null,
    val messages: List<Message> = emptyList(),
    val selectedMessageIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    val selectionScope: SelectionScope = SelectionScope.MESSAGES,
    val replyText: String = "",
    val isSending: Boolean = false,
    val showDefaultSmsDialog: Boolean = false,
    val expandedTimestampIds: Set<Long> = emptySet(),
    val reactionPickerMessageId: Long? = null,
    val reactionPickerBubbleY: Float = 0f,
    val highlightedMessageId: Long? = null,
    // Pending outgoing MMS attachment (URI string + MIME type). Null when composing plain SMS.
    val pendingAttachmentUri: String? = null,
    val pendingMimeType: String? = null
)

/**
 * ViewModel for the thread (conversation) screen.
 *
 * Owns all mutable state for message selection, the reply bar, the emoji reaction picker,
 * and the timestamp display preference. Delegates persistence to [ThreadRepository] and
 * [MessageRepository], and SMS sending to [SmsManagerWrapper].
 *
 * The public [uiState] StateFlow is a flat [ThreadUiState] snapshot assembled by combining
 * several internal flows so that [ThreadContent] can be a fully stateless composable.
 */
@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val smsManagerWrapper: SmsManagerWrapper,
    private val mmsManagerWrapper: MmsManagerWrapper,
    private val timestampPrefRepo: TimestampPreferenceRepository
) : ViewModel() {

    private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

    init {
        // Mark all messages in this thread as read as soon as the thread is opened.
        viewModelScope.launch { messageRepository.markAllRead(threadId) }
    }

    private val _selectionState  = MutableStateFlow(emptySet<Long>())
    private val _isSelectionMode = MutableStateFlow(false)
    private val _selectionScope  = MutableStateFlow(SelectionScope.MESSAGES)
    private val _replyText       = MutableStateFlow("")
    private val _isSending       = MutableStateFlow(false)
    private val _showDefaultSmsDialog     = MutableStateFlow(false)
    private val _expandedTimestampIds     = MutableStateFlow(emptySet<Long>())
    private val _reactionPickerMessageId  = MutableStateFlow<Long?>(null)
    private val _reactionPickerBubbleY    = MutableStateFlow(0f)
    private val _highlightedMessageId     = MutableStateFlow<Long?>(null)
    // URI string + MIME type of a pending outgoing MMS attachment; null when composing SMS.
    private val _pendingAttachmentUri = MutableStateFlow<String?>(null)
    private val _pendingMimeType      = MutableStateFlow<String?>(null)

    /* Fires once per send so the UI unconditionally scrolls to the bottom
     * regardless of the current scroll position. extraBufferCapacity=1 means
     * the emit never suspends even if the collector hasn't consumed yet. */
    private val _scrollToBottomEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToBottomEvent: SharedFlow<Unit> = _scrollToBottomEvent.asSharedFlow()

    val timestampPreference: StateFlow<TimestampPreference> = timestampPrefRepo.preference

    val activeDates: StateFlow<Set<LocalDate>> = messageRepository
        .observeByThread(threadId)
        .map { messages ->
            messages.mapTo(mutableSetOf()) { msg ->
                Instant.ofEpochMilli(msg.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val quickReactionEmojis: StateFlow<List<String>> = messageRepository
        .observeTopUserEmojis()
        .map { topEmojis -> buildQuickEmojiList(topEmojis) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_QUICK_EMOJIS)

    // Named holder for the action-related sub-state to stay within combine's 5-arg limit.
    private data class InnerState(
        val replyText: String,
        val isSending: Boolean,
        val showDefaultSmsDialog: Boolean,
        val expandedTimestampIds: Set<Long>,
        val selectionScope: SelectionScope,
        val reactionPickerMessageId: Long?,
        val reactionPickerBubbleY: Float,
        val highlightedMessageId: Long?,
        val pendingAttachmentUri: String?,
        val pendingMimeType: String?
    )

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ThreadUiState> = combine(
        threadRepository.observeById(threadId),
        messageRepository.observeByThread(threadId),
        _selectionState,
        _isSelectionMode,
        combine(
            _replyText,
            _isSending,
            _showDefaultSmsDialog,
            _expandedTimestampIds,
            _selectionScope,
            _reactionPickerMessageId,
            _reactionPickerBubbleY,
            _highlightedMessageId,
            _pendingAttachmentUri,
            _pendingMimeType
        ) { arr ->
            InnerState(
                replyText               = arr[0] as String,
                isSending               = arr[1] as Boolean,
                showDefaultSmsDialog    = arr[2] as Boolean,
                expandedTimestampIds    = arr[3] as Set<Long>,
                selectionScope          = arr[4] as SelectionScope,
                reactionPickerMessageId = arr[5] as Long?,
                reactionPickerBubbleY   = arr[6] as Float,
                highlightedMessageId    = arr[7] as Long?,
                pendingAttachmentUri    = arr[8] as String?,
                pendingMimeType         = arr[9] as String?
            )
        }
    ) { thread, messages, selected, selectionMode, inner ->
        ThreadUiState(
            thread = thread,
            messages = messages,
            selectedMessageIds = selected,
            isSelectionMode = selectionMode,
            selectionScope = inner.selectionScope,
            replyText = inner.replyText,
            isSending = inner.isSending,
            showDefaultSmsDialog = inner.showDefaultSmsDialog,
            expandedTimestampIds = inner.expandedTimestampIds,
            reactionPickerMessageId = inner.reactionPickerMessageId,
            reactionPickerBubbleY   = inner.reactionPickerBubbleY,
            highlightedMessageId    = inner.highlightedMessageId,
            pendingAttachmentUri    = inner.pendingAttachmentUri,
            pendingMimeType         = inner.pendingMimeType
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())

    // ── Selection ─────────────────────────────────────────────────────────────

    /** Standard toolbar entry — no message pre-selected. */
    fun enterSelectionMode() {
        _isSelectionMode.value = true
        _selectionScope.value  = SelectionScope.MESSAGES
    }

    /** Long-press entry — selects [messageId] immediately. */
    fun enterSelectionModeWithMessage(messageId: Long) {
        _isSelectionMode.value = true
        _selectionScope.value  = SelectionScope.MESSAGES
        _selectionState.update { it + messageId }
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectionState.value  = emptySet()
        _selectionScope.value  = SelectionScope.MESSAGES
    }

    fun toggleSelection(messageId: Long) {
        _selectionState.update { cur ->
            if (messageId in cur) cur - messageId else cur + messageId
        }
    }

    /**
     * Toggles all [ids] as a unit:
     *   • if every id is already selected → deselect all of them
     *   • otherwise → select all of them
     */
    fun toggleMessageIds(ids: List<Long>) {
        val idSet = ids.toSet()
        _selectionState.update { cur ->
            if (idSet.all { it in cur }) cur - idSet else cur + idSet
        }
    }

    /**
     * Changes the active selection scope.
     * Switching to [SelectionScope.ALL] immediately selects every loaded message;
     * tapping ALL again when everything is already selected clears the selection
     * and reverts to MESSAGES scope.
     */
    fun setSelectionScope(scope: SelectionScope) {
        if (scope == SelectionScope.ALL) {
            val allIds = uiState.value.messages.map { it.id }.toSet()
            if (_selectionState.value == allIds) {
                _selectionState.value = emptySet()
                _selectionScope.value = SelectionScope.MESSAGES
            } else {
                _selectionState.value = allIds
                _selectionScope.value = SelectionScope.ALL
            }
        } else {
            _selectionScope.value = scope
        }
    }

    fun clearSelection() = exitSelectionMode()

    // ── Reactions ─────────────────────────────────────────────────────────────

    fun showReactionPicker(messageId: Long, bubbleY: Float) {
        _reactionPickerMessageId.value = messageId
        _reactionPickerBubbleY.value   = bubbleY
        _selectionState.value          = setOf(messageId)
    }

    fun dismissReactionPicker() {
        _reactionPickerMessageId.value = null
        _reactionPickerBubbleY.value   = 0f
        _selectionState.value          = emptySet()
    }

    /** Transitions from single-message action mode into full multi-select mode. */
    fun enterSelectionModeFromActionMode() {
        _reactionPickerMessageId.value = null
        _reactionPickerBubbleY.value   = 0f
        _isSelectionMode.value         = true
        _selectionScope.value          = SelectionScope.MESSAGES
        // _selectionState already contains the long-pressed message
    }

    /** Stub — forward a message to another conversation. */
    fun forwardMessage(messageId: Long) {
        dismissReactionPicker()
        // TODO: navigate to contact picker and send message copy
    }

    fun toggleReaction(messageId: Long, emoji: String) {
        viewModelScope.launch {
            val message = uiState.value.messages.find { it.id == messageId } ?: return@launch
            val myReaction = message.reactions.find {
                it.senderAddress == SELF_ADDRESS && it.emoji == emoji
            }
            if (myReaction != null) {
                messageRepository.deleteReaction(messageId, SELF_ADDRESS, emoji)
            } else {
                messageRepository.insertReaction(
                    Reaction(
                        id = 0,
                        messageId = messageId,
                        senderAddress = SELF_ADDRESS,
                        emoji = emoji,
                        timestamp = System.currentTimeMillis(),
                        rawText = ""
                    )
                )
            }
            dismissReactionPicker()
        }
    }

    // ── Timestamps ────────────────────────────────────────────────────────────

    fun toggleTimestamp(messageId: Long) {
        _expandedTimestampIds.update { cur ->
            if (messageId in cur) cur - messageId else cur + messageId
        }
    }

    // ── Day select (kept for backward compatibility) ──────────────────────────

    fun selectDay(dayStartMs: Long, dayEndMs: Long) {
        viewModelScope.launch {
            val dayMessages = messageRepository.getByThreadAndDateRange(threadId, dayStartMs, dayEndMs)
            _selectionState.update { cur -> cur + dayMessages.map { it.id } }
        }
    }

    // ── Date range selection ──────────────────────────────────────────────────

    fun selectByDateRange(start: LocalDate, end: LocalDate) {
        viewModelScope.launch {
            val (startMs, endMs) = epochMsForDayBoundaries(start, end)
            val messages = messageRepository.getByThreadAndDateRange(threadId, startMs, endMs)
            _selectionState.update { cur -> cur + messages.map { it.id } }
        }
    }

    // ── Reply / Send ──────────────────────────────────────────────────────────

    fun onReplyTextChanged(text: String) { _replyText.value = text }

    fun dismissDefaultSmsDialog() { _showDefaultSmsDialog.value = false }

    /**
     * Stores the URI and MIME type of a media file the user has chosen to attach.
     * Clears automatically after [sendMessage] picks it up, or via [clearAttachment].
     *
     * Triggers: user taps the attach button and picks a photo/audio/video file.
     */
    fun onAttachmentSelected(uri: Uri, mimeType: String) {
        _pendingAttachmentUri.value = uri.toString()
        _pendingMimeType.value      = mimeType
    }

    /** Clears any pending attachment (user taps the × on the preview chip). */
    fun clearAttachment() {
        _pendingAttachmentUri.value = null
        _pendingMimeType.value      = null
    }

    /**
     * Sends the current reply text and/or pending attachment.
     *
     * Chooses the MMS or SMS path automatically. Inserts an optimistic [Message]
     * (negative ID) so the UI shows the message immediately, then dispatches it
     * to the radio. On local failure the optimistic message is marked FAILED so
     * the user sees the red-! retry indicator.
     */
    fun sendMessage() {
        val text             = _replyText.value.trim()
        val attachmentUri    = _pendingAttachmentUri.value
        val mimeType         = _pendingMimeType.value
        // Require at least text OR an attachment; also block re-entrant sends.
        if ((text.isEmpty() && attachmentUri == null) || _isSending.value) return

        if (!isDefaultSmsApp()) {
            _showDefaultSmsDialog.value = true
            return
        }

        val thread = uiState.value.thread ?: return
        _replyText.value = ""
        clearAttachment()

        viewModelScope.launch {
            _isSending.value = true
            val now    = System.currentTimeMillis()
            val tempId = -now

            if (attachmentUri != null && mimeType != null) {
                // ── MMS path ──────────────────────────────────────────────────
                // Optimistic message shown immediately with attachment preview.
                val optimistic = Message(
                    id             = tempId,
                    threadId       = threadId,
                    address        = thread.address,
                    body           = text,
                    timestamp      = now,
                    isSent         = true,
                    type           = Telephony.Mms.MESSAGE_BOX_SENT,
                    deliveryStatus = DELIVERY_STATUS_PENDING,
                    isMms          = true,
                    attachmentUri  = attachmentUri,
                    mimeType       = mimeType
                )
                messageRepository.insert(optimistic)
                /* Signal scroll AFTER the insert so the message is already in the list
                 * when the UI receives the event — avoids a frame where the scroll lands
                 * on the old last message instead of the new one. */
                _scrollToBottomEvent.tryEmit(Unit)
                /* PendingIntent for MmsSentReceiver — updates Room when the MMSC
                 * responds. EXTRA_SENT_AT_MS lets the receiver find the real content-
                 * provider row even if sync replaced the temp row before MMSC replied. */
                val reqCode   = (tempId and 0x3FFF_FFFFL).toInt()
                /* Snapshot the max MMS _id before sending so MmsSentReceiver can find
                 * the real content://mms row without relying on the date field format
                 * (seconds vs milliseconds varies by device/OEM). */
                val beforeSendMaxId = try {
                    context.contentResolver.query(
                        android.net.Uri.parse("content://mms"),
                        arrayOf("_id"), null, null, "_id DESC LIMIT 1"
                    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
                } catch (_: Exception) { 0L }
                val sentIntent = PendingIntent.getBroadcast(
                    context, reqCode,
                    Intent(context, MmsSentReceiver::class.java).apply {
                        action = MmsSentReceiver.ACTION_MMS_SENT
                        putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, tempId)
                        putExtra(MmsSentReceiver.EXTRA_SENT_AT_MS, now)
                        putExtra(MmsSentReceiver.EXTRA_BEFORE_SEND_MAX_ID, beforeSendMaxId)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
                val dispatched = mmsManagerWrapper.sendMms(
                    toAddress     = thread.address,
                    textBody      = text,
                    attachmentUri = Uri.parse(attachmentUri),
                    mimeType      = mimeType,
                    messageId     = tempId,
                    sentIntent    = sentIntent
                )
                /* Local failure (bad URI, IO error, telephony exception): mark FAILED
                 * immediately so the message doesn't stay stuck as PENDING forever. */
                if (!dispatched) {
                    messageRepository.updateDeliveryStatus(tempId, DELIVERY_STATUS_FAILED)
                } else {
                    /* Pin the optimistic row's attachmentUri to the stable filesDir cache
                     * file written by MmsManagerWrapper. Samsung's content://mms/part/ data
                     * for sent messages is often empty — using our own cached bytes ensures
                     * the image stays visible after SmsSyncHandler swaps the real row in. */
                    try {
                        val cacheFile = File(context.filesDir, "mms_attach_$tempId.bin")
                        if (cacheFile.exists()) {
                            val stableUri = FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", cacheFile
                            ).toString()
                            messageRepository.updateAttachmentUri(tempId, stableUri)
                        }
                    } catch (_: Exception) { /* keep original picker URI on error */ }
                }
            } else {
                // ── SMS path (existing behaviour) ─────────────────────────────
                val optimistic = Message(
                    id             = tempId,
                    threadId       = threadId,
                    address        = thread.address,
                    body           = text,
                    timestamp      = now,
                    isSent         = true,
                    type           = Telephony.Sms.MESSAGE_TYPE_SENT,
                    deliveryStatus = DELIVERY_STATUS_PENDING
                )
                messageRepository.insert(optimistic)
                /* Signal scroll AFTER the insert — same rationale as the MMS path above. */
                _scrollToBottomEvent.tryEmit(Unit)
                smsManagerWrapper.sendTextMessage(thread.address, text, tempId)
            }
            _isSending.value = false
        }
    }

    /**
     * Re-sends a message whose [deliveryStatus] is [DELIVERY_STATUS_FAILED].
     *
     * For MMS: rebuilds the PendingIntent (the original FLAG_ONE_SHOT was consumed)
     * and re-invokes [MmsManagerWrapper] with the same attachment URI.
     * For SMS: calls [SmsManagerWrapper.sendTextMessage] directly.
     */
    fun retrySend(messageId: Long) {
        val message = uiState.value.messages.find { it.id == messageId } ?: return
        if (message.deliveryStatus != DELIVERY_STATUS_FAILED) return
        viewModelScope.launch {
            messageRepository.updateDeliveryStatus(messageId, DELIVERY_STATUS_PENDING)
            if (message.isMms && message.attachmentUri != null) {
                /* MMS retry: rebuild the sentIntent (the original was FLAG_ONE_SHOT and
                 * has already been consumed) then re-invoke MmsManagerWrapper with the
                 * same attachment URI. */
                val reqCode = (messageId and 0x3FFF_FFFFL).toInt()
                val beforeSendMaxId = try {
                    context.contentResolver.query(
                        android.net.Uri.parse("content://mms"),
                        arrayOf("_id"), null, null, "_id DESC LIMIT 1"
                    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
                } catch (_: Exception) { 0L }
                val sentIntent = PendingIntent.getBroadcast(
                    context, reqCode,
                    Intent(context, MmsSentReceiver::class.java).apply {
                        action = MmsSentReceiver.ACTION_MMS_SENT
                        putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, messageId)
                        putExtra(MmsSentReceiver.EXTRA_SENT_AT_MS, System.currentTimeMillis())
                        putExtra(MmsSentReceiver.EXTRA_BEFORE_SEND_MAX_ID, beforeSendMaxId)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
                val dispatched = mmsManagerWrapper.sendMms(
                    toAddress     = message.address,
                    textBody      = message.body,
                    attachmentUri = Uri.parse(message.attachmentUri),
                    mimeType      = message.mimeType ?: "image/jpeg",
                    messageId     = messageId,
                    sentIntent    = sentIntent
                )
                if (!dispatched) {
                    messageRepository.updateDeliveryStatus(messageId, DELIVERY_STATUS_FAILED)
                }
            } else {
                // SMS retry: re-send as plain text.
                smsManagerWrapper.sendTextMessage(message.address, message.body, messageId)
            }
        }
    }

    private fun isDefaultSmsApp(): Boolean {
        /* On Android 10+ the RoleManager is the authoritative source —
         * getDefaultSmsPackage can lag after the role is granted via the system dialog. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm.isRoleHeld(RoleManager.ROLE_SMS)) return true
        }
        return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    // ── Backup policy ─────────────────────────────────────────────────────────

    fun updateBackupPolicy(policy: BackupPolicy) {
        viewModelScope.launch {
            threadRepository.updateBackupPolicy(threadId, policy)
        }
    }

    // ── Mute / Pin / Notifications ────────────────────────────────────────────

    fun toggleMute() {
        val current = uiState.value.thread?.isMuted ?: return
        viewModelScope.launch {
            threadRepository.updateMuted(threadId, !current)
        }
    }


    fun togglePin() {
        val current = uiState.value.thread?.isPinned ?: return
        viewModelScope.launch {
            threadRepository.updatePinned(threadId, !current)
        }
    }

    /** Flips [notificationsEnabled] for this thread. Called from the thread ⋮ menu.
     *  When set to false, [SmsReceiver] will skip posting any notification for this number. */
    fun toggleNotificationsEnabled() {
        val current = uiState.value.thread?.notificationsEnabled ?: return
        viewModelScope.launch {
            threadRepository.updateNotificationsEnabled(threadId, !current)
        }
    }

    // ── Highlight (scroll-jump target) ────────────────────────────────────────

    fun highlightMessage(messageId: Long) {
        _highlightedMessageId.value = messageId
        viewModelScope.launch {
            delay(2_000)
            _highlightedMessageId.compareAndSet(messageId, null)
        }
    }

    companion object {
        val DEFAULT_QUICK_EMOJIS = listOf("❤️", "👍", "😂", "😮", "🔥")

        /**
         * Merges [topUsed] (most-used first) with [defaults], deduplicating, and caps the result
         * at [limit]. Extracted here so it can be tested without constructing the ViewModel.
         */
        internal fun buildQuickEmojiList(
            topUsed: List<String>,
            defaults: List<String> = DEFAULT_QUICK_EMOJIS,
            limit: Int = 5
        ): List<String> {
            val merged = topUsed.toMutableList()
            defaults.forEach { if (it !in merged) merged.add(it) }
            return merged.take(limit)
        }

        /**
         * Converts a [LocalDate] range into an inclusive epoch-millisecond range.
         * Start is midnight of [start] in the system timezone; end is one millisecond
         * before midnight of the day after [end].
         *
         * Extracted to the companion object so it can be tested without constructing
         * the full ViewModel.
         */
        internal fun epochMsForDayBoundaries(start: LocalDate, end: LocalDate): Pair<Long, Long> {
            val startMs = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMs   = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
            return startMs to endMs
        }
    }
}
