package com.plusorminustwo.postmark.ui.contact

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [ContactDetailScreen].
 *
 * Exposes the live [Thread] state and the list of media-bearing messages for the
 * shared-media grid. Mute / pin / notifications / nickname changes are all
 * delegated to the same repository layer used by [ThreadViewModel] so they stay
 * in sync with no extra coordination.
 *
 * @param savedStateHandle  Carries the `threadId` nav argument from [AppNavigation].
 * @param threadRepository  Reads / writes thread-level metadata.
 * @param messageRepository Reads media messages for the shared-media grid.
 */
@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    // ── Thread ID ─────────────────────────────────────────────────────────────
    // Pulled from the nav back-stack; guaranteed non-null by the navigation graph.
    private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

    // ── Live state ────────────────────────────────────────────────────────────

    /** Live thread metadata — name, muted, pinned, notifications, nickname. */
    val thread: StateFlow<Thread?> = threadRepository
        .observeById(threadId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Messages that carry a media attachment (images, video, audio), newest first. */
    val mediaMessages: StateFlow<List<Message>> = messageRepository
        .observeMediaMessages(threadId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Nickname ──────────────────────────────────────────────────────────────

    /**
     * Saves a Postmark-only display alias for this thread.
     * Passing null or a blank string clears the existing nickname.
     */
    fun setNickname(nickname: String?) {
        val normalized = nickname?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch { threadRepository.setNickname(threadId, normalized) }
    }

    // ── Toggles (mirror the ⋮ menu in ThreadScreen) ───────────────────────────

    /** Toggles the muted state; no-op if the thread hasn't loaded yet. */
    fun toggleMute() {
        val current = thread.value?.isMuted ?: return
        viewModelScope.launch { threadRepository.updateMuted(threadId, !current) }
    }

    /** Toggles the pinned state; no-op if the thread hasn't loaded yet. */
    fun togglePin() {
        val current = thread.value?.isPinned ?: return
        viewModelScope.launch { threadRepository.updatePinned(threadId, !current) }
    }

    /**
     * Toggles the per-thread notifications flag.
     * When false the thread is fully silenced — no notification is ever posted.
     */
    fun toggleNotifications() {
        val current = thread.value?.notificationsEnabled ?: return
        viewModelScope.launch { threadRepository.updateNotificationsEnabled(threadId, !current) }
    }
}
