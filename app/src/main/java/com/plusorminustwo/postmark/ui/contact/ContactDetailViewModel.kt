package com.plusorminustwo.postmark.ui.contact

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.customization.BackgroundPlacement
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.service.customization.ChatBackgroundImageStore
import com.plusorminustwo.postmark.ui.components.PlacementRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
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
    private val messageRepository: MessageRepository,
    private val imageStore: ChatBackgroundImageStore
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

    // ── Accent color (contact color: avatar + received bubbles) ────────────────

    /**
     * Saves a Postmark-only contact color (ARGB) for this thread — used for the
     * contact's avatar and their received-message bubbles.
     * Passing null clears it, falling back to the default hash-derived color.
     */
    fun setAccentColor(argb: Int?) {
        viewModelScope.launch { threadRepository.setAccentColor(threadId, argb) }
    }

    // ── Sent-bubble color ────────────────────────────────────────────────────

    /**
     * Saves a Postmark-only sent-bubble color (ARGB) for this thread, independent
     * of [setAccentColor]. Passing null clears it, falling back to the app default
     * (primaryContainer).
     */
    fun setSentColor(argb: Int?) {
        viewModelScope.launch { threadRepository.setSentColor(threadId, argb) }
    }

    // ── Chat background ───────────────────────────────────────────────────────

    /**
     * Saves a per-thread chat background override.
     * Passing null clears it, falling back to the global default.
     */
    fun setChatBackground(id: String?) {
        viewModelScope.launch { applyChatBackground(id) }
    }

    // ── Placement editor ──────────────────────────────────────────────────────

    /** A pending placement-editor request, or null when the editor is closed. */
    private val _placementRequest = MutableStateFlow<PlacementRequest?>(null)
    val placementRequest: StateFlow<PlacementRequest?> = _placementRequest

    /**
     * Opens the placement editor for a freshly-picked gallery [uri]. Reads its oriented size
     * (silent no-op if unreadable — the store logs it) and opens on [BackgroundPlacement.FILL].
     */
    fun beginPlacementForPick(uri: Uri) {
        viewModelScope.launch {
            val (w, h) = imageStore.orientedSize(uri) ?: return@launch
            _placementRequest.value = PlacementRequest(
                model = uri, imageWidth = w, imageHeight = h,
                initial = BackgroundPlacement.FILL, sourceUri = uri, adjustId = null
            )
        }
    }

    /**
     * Re-opens the editor for the CURRENT image background to adjust its placement. A no-op
     * unless the thread's background is an image id; opens on the stored placement, or
     * [BackgroundPlacement.FILL] for a legacy image with no sidecar.
     */
    fun beginPlacementForAdjust() {
        val id = thread.value?.chatBackgroundId ?: return
        if (!ChatBackgrounds.isImageId(id)) return
        viewModelScope.launch {
            val srcFile = imageStore.srcFileFor(id) ?: return@launch
            val (w, h) = imageStore.sourceSize(srcFile) ?: return@launch
            _placementRequest.value = PlacementRequest(
                model = srcFile, imageWidth = w, imageHeight = h,
                initial = imageStore.placementFor(id) ?: BackgroundPlacement.FILL,
                sourceUri = null, adjustId = id
            )
        }
    }

    /** Dismisses the placement editor without changing the background. */
    fun cancelPlacement() { _placementRequest.value = null }

    /**
     * Bakes [p] over the editor viewport and applies the result as this thread's override:
     * a fresh save for a pick, or a re-bake for an adjust. The existing [applyChatBackground]
     * garbage-collects the previous image. A save failure just closes the editor.
     */
    fun confirmPlacement(p: BackgroundPlacement, vw: Int, vh: Int) {
        val request = _placementRequest.value ?: return
        viewModelScope.launch {
            val newId = when {
                request.sourceUri != null -> imageStore.saveWithPlacement(request.sourceUri, p, vw, vh)
                request.adjustId != null -> imageStore.rebakeWithPlacement(request.adjustId, p, vw, vh)
                else -> null
            }
            if (newId != null) applyChatBackground(newId)
            _placementRequest.value = null
        }
    }

    /** Resolved file for a custom-image [id] (for the dialog thumbnail); null if missing. */
    fun chatBackgroundImageFile(id: String): File? = imageStore.fileFor(id)

    /** Overwrites the per-thread background, then lets the store garbage-collect the
     *  PREVIOUS image if it was a custom one now referenced by nothing. */
    private suspend fun applyChatBackground(id: String?) {
        val old = thread.value?.chatBackgroundId
        threadRepository.setChatBackground(threadId, id)
        imageStore.cleanupAfterChange(old, id)
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
