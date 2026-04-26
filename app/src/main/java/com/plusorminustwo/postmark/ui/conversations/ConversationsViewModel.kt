package com.plusorminustwo.postmark.ui.conversations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.sync.FirstLaunchSyncWorker
import com.plusorminustwo.postmark.domain.model.Thread
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    threadRepository: ThreadRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)

    val threads: StateFlow<List<Thread>> = threadRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isSyncing: StateFlow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(FirstLaunchSyncWorker.WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun triggerSync() {
        context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)
            .edit().remove("first_sync_completed").apply()
        workManager.enqueueUniqueWork(
            FirstLaunchSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            FirstLaunchSyncWorker.buildRequest()
        )
    }
}
