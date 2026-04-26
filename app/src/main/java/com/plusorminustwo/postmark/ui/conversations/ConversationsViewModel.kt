package com.plusorminustwo.postmark.ui.conversations

import android.content.Context
import android.provider.Telephony
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.sync.FirstLaunchSyncWorker
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val prefs get() = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    val threads: StateFlow<List<Thread>> = threadRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isSyncing: StateFlow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(FirstLaunchSyncWorker.WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Last known sync result — reads from SharedPreferences (written by the worker),
    // and updates live when a new work run completes.
    val syncStatus: StateFlow<String?> = workManager
        .getWorkInfosForUniqueWorkFlow(FirstLaunchSyncWorker.WORK_NAME)
        .map { infos ->
            val latest = infos.firstOrNull()
            when (latest?.state) {
                WorkInfo.State.SUCCEEDED ->
                    latest.outputData.getString(FirstLaunchSyncWorker.KEY_STATUS)
                        ?: prefs.getString(FirstLaunchSyncWorker.KEY_STATUS, null)
                WorkInfo.State.FAILED ->
                    latest.outputData.getString(FirstLaunchSyncWorker.KEY_ERROR)
                        ?.let { "Error: $it" }
                        ?: prefs.getString(FirstLaunchSyncWorker.KEY_STATUS, null)
                else -> prefs.getString(FirstLaunchSyncWorker.KEY_STATUS, null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefs.getString(FirstLaunchSyncWorker.KEY_STATUS, null))

    fun triggerSync() {
        prefs.edit().remove("first_sync_completed").apply()
        workManager.enqueueUniqueWork(
            FirstLaunchSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            FirstLaunchSyncWorker.buildRequest()
        )
    }

    fun loadSampleData() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val min = 60_000L
            val hr = 60 * min
            val day = 24 * hr

            val sampleThreads = listOf(
                Thread(9_001L, "Sarah Johnson", "+12125550101", now - 20 * min, "Sounds good, see you then!", BackupPolicy.GLOBAL),
                Thread(9_002L, "Mike Chen",     "+13105550102", now - day - 3 * hr, "Haha yeah that was wild", BackupPolicy.GLOBAL),
                Thread(9_003L, "Mom",           "+14045550103", now - 3 * day, "Love you too sweetheart ❤️", BackupPolicy.GLOBAL),
                Thread(9_004L, "Alex Rivera",   "+15105550104", now - 7 * day, "Let me know when you're free", BackupPolicy.GLOBAL),
                Thread(9_005L, "David Park",    "+16175550105", now - 14 * day, "Thanks for the help yesterday", BackupPolicy.GLOBAL),
            )
            threadRepository.upsertAll(sampleThreads)

            val rx = Telephony.Sms.MESSAGE_TYPE_INBOX
            val tx = Telephony.Sms.MESSAGE_TYPE_SENT

            val sampleMessages = listOf(
                // Sarah Johnson — thread 9001
                msg(90_001, 9_001, "+12125550101", "Hey, are you coming to the party tonight?", now - 2 * hr, false, rx),
                msg(90_002, 9_001, "+12125550101", "Yeah for sure! What time does it start?", now - 110 * min, true, tx),
                msg(90_003, 9_001, "+12125550101", "Around 8. Bring something to drink 🍻", now - 90 * min, false, rx),
                msg(90_004, 9_001, "+12125550101", "Will do! Should I bring anyone?", now - 60 * min, true, tx),
                msg(90_005, 9_001, "+12125550101", "The more the merrier!", now - 30 * min, false, rx),
                msg(90_006, 9_001, "+12125550101", "Sounds good, see you then!", now - 20 * min, true, tx),

                // Mike Chen — thread 9002
                msg(90_010, 9_002, "+13105550102", "Did you watch the game last night?", now - day - 5 * hr, false, rx),
                msg(90_011, 9_002, "+13105550102", "Oh man yes, that last quarter was insane", now - day - 4 * hr, true, tx),
                msg(90_012, 9_002, "+13105550102", "I couldn't believe that call at the end", now - day - 4 * hr + 5 * min, false, rx),
                msg(90_013, 9_002, "+13105550102", "Haha yeah that was wild", now - day - 3 * hr, true, tx),

                // Mom — thread 9003
                msg(90_020, 9_003, "+14045550103", "Hi honey, are you eating well?", now - 4 * day, false, rx),
                msg(90_021, 9_003, "+14045550103", "Yes mom, I'm fine 😄", now - 4 * day + hr, true, tx),
                msg(90_022, 9_003, "+14045550103", "Good! Call me this weekend?", now - 3 * day + 2 * hr, false, rx),
                msg(90_023, 9_003, "+14045550103", "Of course, love you!", now - 3 * day + 3 * hr, true, tx),
                msg(90_024, 9_003, "+14045550103", "Love you too sweetheart ❤️", now - 3 * day, false, rx),

                // Alex Rivera — thread 9004
                msg(90_030, 9_004, "+15105550104", "Hey, want to grab lunch this week?", now - 8 * day, true, tx),
                msg(90_031, 9_004, "+15105550104", "I'd love to! Maybe Thursday?", now - 8 * day + hr, false, rx),
                msg(90_032, 9_004, "+15105550104", "Thursday works. How's noon?", now - 7 * day + hr, true, tx),
                msg(90_033, 9_004, "+15105550104", "Let me know when you're free", now - 7 * day, false, rx),

                // David Park — thread 9005
                msg(90_040, 9_005, "+16175550105", "Hey man, I really appreciated your help moving", now - 15 * day, false, rx),
                msg(90_041, 9_005, "+16175550105", "Of course, anytime!", now - 14 * day + hr, true, tx),
                msg(90_042, 9_005, "+16175550105", "Thanks for the help yesterday", now - 14 * day, false, rx),
            )
            messageRepository.insertAll(sampleMessages)
        }
    }

    private fun msg(id: Long, threadId: Long, address: String, body: String, ts: Long, isSent: Boolean, type: Int) =
        Message(id, threadId, address, body, ts, isSent, type)
}
