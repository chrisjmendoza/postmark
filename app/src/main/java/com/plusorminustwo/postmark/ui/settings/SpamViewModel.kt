package com.plusorminustwo.postmark.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.Thread
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Spam folder screen. Exposes the (reactive) list of spam threads and
 * a single restore action. All the hiding/silencing behaviour lives in the shared
 * [ThreadRepository]/query layer — this VM only reads the spam list and flips the flag back.
 */
@HiltViewModel
class SpamViewModel @Inject constructor(
    private val threadRepository: ThreadRepository
) : ViewModel() {

    /** Null until the first Room emission so the UI can distinguish "loading" from "empty". */
    val spamThreads: StateFlow<List<Thread>?> = threadRepository.observeSpam()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Restores a thread out of the Spam folder back into the main conversation list. */
    fun notSpam(threadId: Long) {
        viewModelScope.launch { threadRepository.updateSpam(threadId, false) }
    }
}
