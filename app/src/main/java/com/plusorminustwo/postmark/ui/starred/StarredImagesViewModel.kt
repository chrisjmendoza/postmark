package com.plusorminustwo.postmark.ui.starred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.domain.model.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** ViewModel for [StarredImagesScreen] — a global (cross-thread) gallery of every
 *  starred image, newest first. */
@HiltViewModel
class StarredImagesViewModel @Inject constructor(
    messageRepository: MessageRepository
) : ViewModel() {

    val starredImages: StateFlow<List<Message>> = messageRepository.observeStarredImages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
