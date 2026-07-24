package com.plusorminustwo.postmark.ui.contact

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.domain.model.GalleryAttachment
import com.plusorminustwo.postmark.domain.model.toGalleryAttachments
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for [PhotoGalleryScreen] — every image attachment exchanged in one thread,
 * newest first. Reuses [MessageRepository.observeMediaMessages], the same read-only
 * query [ContactDetailViewModel]'s Photos section preview is built from; no new DAO query.
 */
@HiltViewModel
class PhotoGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    messageRepository: MessageRepository
) : ViewModel() {
    private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

    val photos: StateFlow<List<GalleryAttachment>> = messageRepository
        .observeMediaMessages(threadId)
        .map { it.toGalleryAttachments("image/") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * ViewModel for [VideoGalleryScreen] — every video attachment exchanged in one thread,
 * newest first. Reuses [MessageRepository.observeMediaMessages], the same read-only
 * query [ContactDetailViewModel]'s Videos section preview is built from; no new DAO query.
 */
@HiltViewModel
class VideoGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    messageRepository: MessageRepository
) : ViewModel() {
    private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

    val videos: StateFlow<List<GalleryAttachment>> = messageRepository
        .observeMediaMessages(threadId)
        .map { it.toGalleryAttachments("video/") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
