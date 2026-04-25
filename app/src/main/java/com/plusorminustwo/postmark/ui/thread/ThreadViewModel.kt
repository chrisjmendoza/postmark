package com.plusorminustwo.postmark.ui.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThreadUiState(
    val thread: Thread? = null,
    val messages: List<Message> = emptyList(),
    val selectedMessageIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false
)

@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

    private val _selectionState = MutableStateFlow(emptySet<Long>())
    private val _isSelectionMode = MutableStateFlow(false)

    val uiState: StateFlow<ThreadUiState> = combine(
        threadRepository.observeById(threadId),
        messageRepository.observeByThread(threadId),
        _selectionState,
        _isSelectionMode
    ) { thread, messages, selected, selectionMode ->
        ThreadUiState(
            thread = thread,
            messages = messages,
            selectedMessageIds = selected,
            isSelectionMode = selectionMode
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())

    fun enterSelectionMode() {
        _isSelectionMode.value = true
    }

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
}
