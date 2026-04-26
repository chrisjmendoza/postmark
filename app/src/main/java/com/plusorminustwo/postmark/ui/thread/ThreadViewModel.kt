package com.plusorminustwo.postmark.ui.thread

import android.content.Context
import android.provider.Telephony
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.service.sms.SmsManagerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThreadUiState(
    val thread: Thread? = null,
    val messages: List<Message> = emptyList(),
    val selectedMessageIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    val replyText: String = "",
    val isSending: Boolean = false,
    val showDefaultSmsDialog: Boolean = false
)

@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val smsManagerWrapper: SmsManagerWrapper
) : ViewModel() {

    private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

    private val _selectionState = MutableStateFlow(emptySet<Long>())
    private val _isSelectionMode = MutableStateFlow(false)
    private val _replyText = MutableStateFlow("")
    private val _isSending = MutableStateFlow(false)
    private val _showDefaultSmsDialog = MutableStateFlow(false)

    val uiState: StateFlow<ThreadUiState> = combine(
        threadRepository.observeById(threadId),
        messageRepository.observeByThread(threadId),
        _selectionState,
        _isSelectionMode,
        combine(_replyText, _isSending, _showDefaultSmsDialog) { text, sending, dialog ->
            Triple(text, sending, dialog)
        }
    ) { thread, messages, selected, selectionMode, (text, sending, dialog) ->
        ThreadUiState(
            thread = thread,
            messages = messages,
            selectedMessageIds = selected,
            isSelectionMode = selectionMode,
            replyText = text,
            isSending = sending,
            showDefaultSmsDialog = dialog
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())

    fun enterSelectionMode() { _isSelectionMode.value = true }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectionState.value = emptySet()
    }

    fun toggleSelection(messageId: Long) {
        _selectionState.update { current ->
            if (messageId in current) current - messageId else current + messageId
        }
    }

    fun selectDay(dayStartMs: Long, dayEndMs: Long) {
        viewModelScope.launch {
            val dayMessages = messageRepository.getByThreadAndDateRange(threadId, dayStartMs, dayEndMs)
            _selectionState.update { current -> current + dayMessages.map { it.id } }
        }
    }

    fun clearSelection() = exitSelectionMode()

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
            val now = System.currentTimeMillis()
            val tempId = -now  // negative ID marks optimistic messages
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
