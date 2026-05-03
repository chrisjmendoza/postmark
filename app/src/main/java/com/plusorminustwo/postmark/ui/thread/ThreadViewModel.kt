package com.plusorminustwo.postmark.ui.thread

import android.content.Context
import android.provider.Telephony
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
    val highlightedMessageId: Long? = null
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
    private val timestampPrefRepo: TimestampPreferenceRepository
) : ViewModel() {

    private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

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
        val highlightedMessageId: Long?
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
            _highlightedMessageId
        ) { arr ->
            InnerState(
                replyText               = arr[0] as String,
                isSending               = arr[1] as Boolean,
                showDefaultSmsDialog    = arr[2] as Boolean,
                expandedTimestampIds    = arr[3] as Set<Long>,
                selectionScope          = arr[4] as SelectionScope,
                reactionPickerMessageId = arr[5] as Long?,
                reactionPickerBubbleY   = arr[6] as Float,
                highlightedMessageId    = arr[7] as Long?
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
            highlightedMessageId    = inner.highlightedMessageId
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

    fun sendMessage() {
        val text = _replyText.value.trim()
        if (text.isEmpty() || _isSending.value) return

        if (!isDefaultSmsApp()) {
            _showDefaultSmsDialog.value = true
            return
        }

        val thread = uiState.value.thread ?: return
        _replyText.value = ""

        viewModelScope.launch {
            _isSending.value = true
            val now    = System.currentTimeMillis()
            val tempId = -now
            val optimistic = Message(
                id = tempId,
                threadId = threadId,
                address = thread.address,
                body = text,
                timestamp = now,
                isSent = true,
                type = Telephony.Sms.MESSAGE_TYPE_SENT,
                deliveryStatus = DELIVERY_STATUS_PENDING
            )
            messageRepository.insert(optimistic)
            smsManagerWrapper.sendTextMessage(thread.address, text, tempId)
            _isSending.value = false
        }
    }

    fun retrySend(messageId: Long) {
        val message = uiState.value.messages.find { it.id == messageId } ?: return
        if (message.deliveryStatus != DELIVERY_STATUS_FAILED) return
        viewModelScope.launch {
            messageRepository.updateDeliveryStatus(messageId, DELIVERY_STATUS_PENDING)
            smsManagerWrapper.sendTextMessage(message.address, message.body, messageId)
        }
    }

    private fun isDefaultSmsApp(): Boolean =
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

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
