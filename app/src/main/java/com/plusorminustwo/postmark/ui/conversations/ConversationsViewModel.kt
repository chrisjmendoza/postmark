package com.plusorminustwo.postmark.ui.conversations

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Conversations (thread list) screen.
 *
 * Responsibilities:
 *  - Exposes the full ordered [threads] list and per-thread [unreadCounts] as reactive
 *    [StateFlow]s for the UI.
 *  - Monitors [FirstLaunchSyncWorker] state to surface the [isSyncing] banner.
 *  - Performs sync recovery on startup: if threads exist but messages are missing (or
 *    vice-versa), it re-enqueues the first-launch sync worker to repair the database.
 */
@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val prefs get() = context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)

    init {
        // If the app currently holds the default SMS role, clear any stale dismissal
        // so the banner can reappear if the role is lost in a future launch.
        if (checkIsDefaultSmsApp()) prefs.edit().remove("role_banner_dismissed").apply()

        // Recovery: re-sync if:
        //   (a) sync marker is set but threads table is empty — original Samsung fallback case.
        //   (b) threads exist but the messages table is completely empty — this happens when the
        //       sync worker crashed between upsertAll(threads) and insertAll(messages), leaving
        //       threads with lastMessagePreviews but no actual Message rows in Room.
        viewModelScope.launch {
            val syncDone      = prefs.getBoolean("first_sync_completed", false)
            val threadsEmpty  = threadRepository.isEmpty()
            val messagesEmpty = messageRepository.getMaxId() == null &&
                                messageRepository.getMaxMmsId() == null
            val needsRecovery = (syncDone && threadsEmpty) || (!threadsEmpty && messagesEmpty)
            if (needsRecovery) {
                android.util.Log.w(
                    "SyncTrigger",
                    "ConversationsViewModel.init: recovery — threadsEmpty=$threadsEmpty " +
                    "messagesEmpty=$messagesEmpty, enqueuing KEEP"
                )
                prefs.edit().remove("first_sync_completed").apply()
                workManager.enqueueUniqueWork(
                    FirstLaunchSyncWorker.WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    FirstLaunchSyncWorker.buildRequest()
                )
            }
        }
    }

    val threads: StateFlow<List<Thread>?> = threadRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Live map of threadId → unread-message count, used by ThreadRow to show badges.
    val unreadCounts: StateFlow<Map<Long, Int>> = messageRepository.observeUnreadCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val isSyncing: StateFlow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(FirstLaunchSyncWorker.WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Live progress data emitted by the worker every 500 rows via setProgress().
    // Null when the worker is not running or hasn't emitted progress yet.
    val syncProgress: StateFlow<SyncProgress?> = workManager
        .getWorkInfosForUniqueWorkFlow(FirstLaunchSyncWorker.WORK_NAME)
        .map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: return@map null
            val data = running.progress
            val done = data.getInt(FirstLaunchSyncWorker.KEY_PROGRESS_DONE, -1)
            if (done < 0) null
            else SyncProgress(
                phase = data.getString(FirstLaunchSyncWorker.KEY_PROGRESS_PHASE) ?: "",
                done  = done,
                total = data.getInt(FirstLaunchSyncWorker.KEY_PROGRESS_TOTAL, 0),
                eta   = data.getString(FirstLaunchSyncWorker.KEY_PROGRESS_ETA) ?: ""
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    // ── Default SMS role ──────────────────────────────────────────────────────
    // Re-checked on every screen resume via refreshDefaultSmsStatus() so the banner
    // clears immediately when the user grants the role and returns to the app.
    private val _isDefaultSmsApp = MutableStateFlow(checkIsDefaultSmsApp())
    val isDefaultSmsApp: StateFlow<Boolean> = _isDefaultSmsApp.asStateFlow()

    /** Called from ConversationsScreen's ON_RESUME lifecycle effect. */
    fun refreshDefaultSmsStatus() {
        _isDefaultSmsApp.value = checkIsDefaultSmsApp()
    }

    private fun checkIsDefaultSmsApp(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    private val _roleBannerDismissed = MutableStateFlow(
        prefs.getBoolean("role_banner_dismissed", false)
    )
    val roleBannerDismissed: StateFlow<Boolean> = _roleBannerDismissed.asStateFlow()

    fun dismissRoleBanner() {
        prefs.edit().putBoolean("role_banner_dismissed", true).apply()
        _roleBannerDismissed.value = true
    }

    fun triggerSync() {
        android.util.Log.i("SyncTrigger", "ConversationsViewModel.triggerSync — enqueuing REPLACE")
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
                // ── Sarah Johnson (thread 9001) ──────────────────────────────────
                // 4 weeks ago
                msg(90_001, 9_001, "+12125550101", "Hey! Did you end up watching that show I recommended?", now - 28 * day, false, rx),
                msg(90_002, 9_001, "+12125550101", "Not yet, been swamped with work stuff", now - 28 * day + hr, true, tx),
                msg(90_003, 9_001, "+12125550101", "You have to, it gets really good in episode 3", now - 28 * day + 2 * hr, false, rx),
                msg(90_004, 9_001, "+12125550101", "Ok ok adding it to the list 😅", now - 28 * day + 3 * hr, true, tx),
                // 3 weeks ago
                msg(90_005, 9_001, "+12125550101", "So did you watch it yet???", now - 21 * day, false, rx),
                msg(90_006, 9_001, "+12125550101", "YES finally watched ep 1, it's ok so far", now - 21 * day + 2 * hr, true, tx),
                msg(90_007, 9_001, "+12125550101", "JUST OK?? Keep going trust me", now - 21 * day + 2 * hr + 10 * min, false, rx),
                msg(90_008, 9_001, "+12125550101", "Haha alright alright I'll keep going", now - 21 * day + 3 * hr, true, tx),
                // 2 weeks ago
                msg(90_009, 9_001, "+12125550101", "Ok I watched through episode 4 last night", now - 14 * day, true, tx),
                msg(90_010, 9_001, "+12125550101", "AND?? 👀", now - 14 * day + 20 * min, false, rx),
                msg(90_011, 9_001, "+12125550101", "You were right. I'm obsessed. How am I just now hearing about this", now - 14 * day + 25 * min, true, tx),
                msg(90_012, 9_001, "+12125550101", "I TOLD YOU", now - 14 * day + 30 * min, false, rx),
                msg(90_013, 9_001, "+12125550101", "I stayed up until 2am finishing season 1", now - 14 * day + day, true, tx),
                msg(90_014, 9_001, "+12125550101", "Haha welcome to the club, season 2 is even better", now - 14 * day + day + hr, false, rx),
                // 10 days ago
                msg(90_015, 9_001, "+12125550101", "Are you going to Emma's thing on Saturday?", now - 10 * day, false, rx),
                msg(90_016, 9_001, "+12125550101", "I think so, haven't confirmed yet. You?", now - 10 * day + hr, true, tx),
                msg(90_017, 9_001, "+12125550101", "Yeah I'll be there! Can you give me a ride?", now - 10 * day + 2 * hr, false, rx),
                msg(90_018, 9_001, "+12125550101", "Of course, I'll pick you up at 7", now - 10 * day + 2 * hr + 15 * min, true, tx),
                msg(90_019, 9_001, "+12125550101", "You're the best ❤️", now - 10 * day + 2 * hr + 20 * min, false, rx),
                // 5 days ago
                msg(90_020, 9_001, "+12125550101", "That party was so fun! Emma's new place is gorgeous", now - 5 * day, false, rx),
                msg(90_021, 9_001, "+12125550101", "Right?? The rooftop view was insane", now - 5 * day + 30 * min, true, tx),
                msg(90_022, 9_001, "+12125550101", "We need to do that more often", now - 5 * day + 45 * min, false, rx),
                msg(90_023, 9_001, "+12125550101", "100%. Also sorry for keeping you out so late haha", now - 5 * day + hr, true, tx),
                msg(90_024, 9_001, "+12125550101", "Are you kidding, best night I've had in months", now - 5 * day + hr + 10 * min, false, rx),
                // Yesterday
                msg(90_025, 9_001, "+12125550101", "Heads up — I'm making a big batch of soup, want some?", now - day + 10 * hr, false, rx),
                msg(90_026, 9_001, "+12125550101", "Wait seriously? Yes please 🙏", now - day + 10 * hr + 20 * min, true, tx),
                msg(90_027, 9_001, "+12125550101", "I'll drop some off around 6 if that works", now - day + 11 * hr, false, rx),
                msg(90_028, 9_001, "+12125550101", "Perfect, I'll be home. You're an angel", now - day + 11 * hr + 5 * min, true, tx),
                // Today
                msg(90_029, 9_001, "+12125550101", "Hey, are you coming to the party tonight?", now - 2 * hr, false, rx),
                msg(90_030, 9_001, "+12125550101", "Yeah for sure! What time does it start?", now - 110 * min, true, tx),
                msg(90_031, 9_001, "+12125550101", "Around 8. Bring something to drink 🍻", now - 90 * min, false, rx),
                msg(90_032, 9_001, "+12125550101", "Will do! Should I bring anyone?", now - 60 * min, true, tx),
                msg(90_033, 9_001, "+12125550101", "The more the merrier!", now - 30 * min, false, rx),
                msg(90_034, 9_001, "+12125550101", "Sounds good, see you then!", now - 20 * min, true, tx),

                // ── Mike Chen (thread 9002) ──────────────────────────────────────
                // 6 days ago
                msg(90_100, 9_002, "+13105550102", "You coming to trivia Thursday?", now - 6 * day, false, rx),
                msg(90_101, 9_002, "+13105550102", "Wouldn't miss it, same team as last time?", now - 6 * day + hr, true, tx),
                msg(90_102, 9_002, "+13105550102", "Yeah, Ryan and Jess are in too", now - 6 * day + 2 * hr, false, rx),
                msg(90_103, 9_002, "+13105550102", "Nice, we're taking that trophy this time", now - 6 * day + 2 * hr + 10 * min, true, tx),
                // 4 days ago (Thursday / trivia night)
                msg(90_104, 9_002, "+13105550102", "Dude we were SO close to winning", now - 4 * day, true, tx),
                msg(90_105, 9_002, "+13105550102", "That last question killed us. Who knows that much about 80s cartoons", now - 4 * day + 30 * min, false, rx),
                msg(90_106, 9_002, "+13105550102", "Apparently the table next to us lol", now - 4 * day + 35 * min, true, tx),
                msg(90_107, 9_002, "+13105550102", "Rematch next week?", now - 4 * day + hr, false, rx),
                msg(90_108, 9_002, "+13105550102", "Absolutely. And I'm studying 80s cartoons", now - 4 * day + hr + 5 * min, true, tx),
                // Yesterday
                msg(90_109, 9_002, "+13105550102", "Did you watch the game last night?", now - day - 5 * hr, false, rx),
                msg(90_110, 9_002, "+13105550102", "Oh man yes, that last quarter was insane", now - day - 4 * hr, true, tx),
                msg(90_111, 9_002, "+13105550102", "I couldn't believe that call at the end", now - day - 4 * hr + 5 * min, false, rx),
                msg(90_112, 9_002, "+13105550102", "Haha yeah that was wild", now - day - 3 * hr, true, tx),

                // ── Mom (thread 9003) ────────────────────────────────────────────
                // 2 weeks ago
                msg(90_200, 9_003, "+14045550103", "Your cousin Jamie is getting married!", now - 14 * day, false, rx),
                msg(90_201, 9_003, "+14045550103", "No way, that's so exciting! When?", now - 14 * day + hr, true, tx),
                msg(90_202, 9_003, "+14045550103", "October! Save the date, the whole family will be there", now - 14 * day + 2 * hr, false, rx),
                msg(90_203, 9_003, "+14045550103", "Already on the calendar 🗓️", now - 14 * day + 3 * hr, true, tx),
                // 10 days ago
                msg(90_204, 9_003, "+14045550103", "Did you get my voicemail?", now - 10 * day, false, rx),
                msg(90_205, 9_003, "+14045550103", "Sorry mom, was in a meeting. Calling you tonight!", now - 10 * day + 2 * hr, true, tx),
                msg(90_206, 9_003, "+14045550103", "Ok sweetheart, talk later ❤️", now - 10 * day + 2 * hr + 10 * min, false, rx),
                // 1 week ago
                msg(90_207, 9_003, "+14045550103", "Dad wants to know if you're free for dinner Sunday", now - 7 * day, false, rx),
                msg(90_208, 9_003, "+14045550103", "Yes! What time should I come over?", now - 7 * day + hr, true, tx),
                msg(90_209, 9_003, "+14045550103", "Come at 5, I'm making your favorite 😊", now - 7 * day + 2 * hr, false, rx),
                msg(90_210, 9_003, "+14045550103", "Can't wait, see you Sunday!", now - 7 * day + 2 * hr + 5 * min, true, tx),
                // 4 days ago
                msg(90_211, 9_003, "+14045550103", "Hi honey, are you eating well?", now - 4 * day, false, rx),
                msg(90_212, 9_003, "+14045550103", "Yes mom, I'm fine 😄", now - 4 * day + hr, true, tx),
                msg(90_213, 9_003, "+14045550103", "Good! Call me this weekend?", now - 3 * day + 2 * hr, false, rx),
                msg(90_214, 9_003, "+14045550103", "Of course, love you!", now - 3 * day + 3 * hr, true, tx),
                msg(90_215, 9_003, "+14045550103", "Love you too sweetheart ❤️", now - 3 * day, false, rx),

                // ── Alex Rivera (thread 9004) ────────────────────────────────────
                msg(90_300, 9_004, "+15105550104", "Hey, want to grab lunch this week?", now - 8 * day, true, tx),
                msg(90_301, 9_004, "+15105550104", "I'd love to! Maybe Thursday?", now - 8 * day + hr, false, rx),
                msg(90_302, 9_004, "+15105550104", "Thursday works. How's noon?", now - 7 * day + hr, true, tx),
                msg(90_303, 9_004, "+15105550104", "Let me know when you're free", now - 7 * day, false, rx),

                // ── David Park (thread 9005) ─────────────────────────────────────
                msg(90_400, 9_005, "+16175550105", "Hey man, I really appreciated your help moving", now - 15 * day, false, rx),
                msg(90_401, 9_005, "+16175550105", "Of course, anytime!", now - 14 * day + hr, true, tx),
                msg(90_402, 9_005, "+16175550105", "Thanks for the help yesterday", now - 14 * day, false, rx),
            )
            messageRepository.insertAll(sampleMessages)
        }
    }

    // ── Thread quick actions ─────────────────────────────────────────────────

    /** Flips the [isPinned] flag for [threadId]. Called from the conversation-list
     *  long-press context menu so the user can pin/unpin without opening the thread. */
    fun togglePin(threadId: Long, currentlyPinned: Boolean) {
        viewModelScope.launch {
            threadRepository.updatePinned(threadId, !currentlyPinned)
        }
    }

    /** Flips the [isMuted] flag for [threadId]. Called from the conversation-list
     *  long-press context menu so the user can mute/unmute without opening the thread. */
    fun toggleMute(threadId: Long, currentlyMuted: Boolean) {
        viewModelScope.launch {
            threadRepository.updateMuted(threadId, !currentlyMuted)
        }
    }

    private fun msg(id: Long, threadId: Long, address: String, body: String, ts: Long, isSent: Boolean, type: Int) =
        Message(id, threadId, address, body, ts, isSent, type)
}

/** Snapshot of in-progress sync data emitted by [FirstLaunchSyncWorker] via setProgress(). */
data class SyncProgress(
    val phase: String,
    val done: Int,
    val total: Int,
    val eta: String
)
