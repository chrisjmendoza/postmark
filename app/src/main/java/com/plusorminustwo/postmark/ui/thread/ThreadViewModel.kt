package com.plusorminustwo.postmark.ui.thread

import android.content.Context
import android.provider.Telephony
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.preferences.TimestampPreferenceRepository
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import com.plusorminustwo.postmark.service.sms.SmsManagerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class ThreadUiState(
    val thread: Thread? = null,
    val messages: List<Message> = emptyList(),
    val selectedMessageIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    val selectionScope: SelectionScope = SelectionScope.MESSAGES,
    val replyText: String = "",
    val isSending: Boolean = false,
    val showDefaultSmsDialog: Boolean = false,
    val expandedTimestampIds: Set<Long> = emptySet()
)

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
    private val _showDefaultSmsDialog  = MutableStateFlow(false)
    private val _expandedTimestampIds  = MutableStateFlow(emptySet<Long>())

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

    // Replaces the previous Triple(Triple(...), ...) hack with a named holder.
    private data class InnerState(
        val replyText: String,
        val isSending: Boolean,
        val showDefaultSmsDialog: Boolean,
        val expandedTimestampIds: Set<Long>,
        val selectionScope: SelectionScope
    )

    val uiState: StateFlow<ThreadUiState> = combine(
        threadRepository.observeById(threadId),
        messageRepository.observeByThread(threadId),
        _selectionState,
        _isSelectionMode,
        combine(_replyText, _isSending, _showDefaultSmsDialog, _expandedTimestampIds, _selectionScope) {
            text, sending, dialog, expandedIds, scope ->
            InnerState(text, sending, dialog, expandedIds, scope)
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
            expandedTimestampIds = inner.expandedTimestampIds
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

    private fun isDefaultSmsApp(): Boolean =
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
}
