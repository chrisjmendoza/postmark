package com.plusorminustwo.postmark.ui.settings

import android.content.Context
import android.provider.Telephony
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.sync.FirstLaunchSyncWorker
import com.plusorminustwo.postmark.data.sync.StatsUpdater
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.search.parser.ReactionFallbackParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DevOptionsViewModel @Inject constructor(
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val statsUpdater: StatsUpdater,
    private val reactionParser: ReactionFallbackParser,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    private val _isRecomputing = MutableStateFlow(false)
    val isRecomputing: StateFlow<Boolean> = _isRecomputing.asStateFlow()

    private val _isReprocessing = MutableStateFlow(false)
    val isReprocessing: StateFlow<Boolean> = _isReprocessing.asStateFlow()

    fun clearFeedback() { _feedback.value = null }

    // ── Stats ─────────────────────────────────────────────────────────────────

    fun recomputeStats() {
        viewModelScope.launch {
            _isRecomputing.value = true
            try {
                statsUpdater.recomputeAll()
                _feedback.value = "Stats recomputed"
            } finally {
                _isRecomputing.value = false
            }
        }
    }

    // ── Sample data ───────────────────────────────────────────────────────────

    fun loadSampleData() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val min = 60_000L
            val hr  = 60 * min
            val day = 24 * hr

            val sampleThreads = listOf(
                Thread(9_001L, "Sarah Johnson", "+12125550101", now - 20 * min,  "Sounds good, see you then!", BackupPolicy.GLOBAL),
                Thread(9_002L, "Mike Chen",     "+13105550102", now - day - 3 * hr, "Haha yeah that was wild", BackupPolicy.GLOBAL),
                Thread(9_003L, "Mom",           "+14045550103", now - 3 * day,   "Love you too sweetheart ❤️", BackupPolicy.GLOBAL),
                Thread(9_004L, "Alex Rivera",   "+15105550104", now - 7 * day,   "Let me know when you're free", BackupPolicy.GLOBAL),
                Thread(9_005L, "David Park",    "+16175550105", now - 14 * day,  "Thanks for the help yesterday", BackupPolicy.GLOBAL),
            )
            threadRepository.upsertAll(sampleThreads)

            val rx = Telephony.Sms.MESSAGE_TYPE_INBOX
            val tx = Telephony.Sms.MESSAGE_TYPE_SENT

            val msgs = listOf(
                // ── Sarah Johnson (thread 9001) ──────────────────────────────
                msg(90_001, 9_001, "+12125550101", "Hey! Did you end up watching that show I recommended?", now - 28 * day, false, rx),
                msg(90_002, 9_001, "+12125550101", "Not yet, been swamped with work stuff", now - 28 * day + hr, true, tx),
                msg(90_003, 9_001, "+12125550101", "You have to, it gets really good in episode 3", now - 28 * day + 2 * hr, false, rx),
                msg(90_004, 9_001, "+12125550101", "Ok ok adding it to the list 😅", now - 28 * day + 3 * hr, true, tx),
                msg(90_005, 9_001, "+12125550101", "So did you watch it yet???", now - 21 * day, false, rx),
                msg(90_006, 9_001, "+12125550101", "YES finally watched ep 1, it's ok so far", now - 21 * day + 2 * hr, true, tx),
                msg(90_007, 9_001, "+12125550101", "JUST OK?? Keep going trust me", now - 21 * day + 2 * hr + 10 * min, false, rx),
                msg(90_008, 9_001, "+12125550101", "Haha alright alright I'll keep going", now - 21 * day + 3 * hr, true, tx),
                msg(90_009, 9_001, "+12125550101", "Ok I watched through episode 4 last night", now - 14 * day, true, tx),
                msg(90_010, 9_001, "+12125550101", "AND?? 👀", now - 14 * day + 20 * min, false, rx),
                msg(90_011, 9_001, "+12125550101", "You were right. I'm obsessed. How am I just now hearing about this", now - 14 * day + 25 * min, true, tx),
                msg(90_012, 9_001, "+12125550101", "I TOLD YOU", now - 14 * day + 30 * min, false, rx),
                msg(90_013, 9_001, "+12125550101", "I stayed up until 2am finishing season 1", now - 14 * day + day, true, tx),
                msg(90_014, 9_001, "+12125550101", "Haha welcome to the club, season 2 is even better", now - 14 * day + day + hr, false, rx),
                msg(90_015, 9_001, "+12125550101", "Are you going to Emma's thing on Saturday?", now - 10 * day, false, rx),
                msg(90_016, 9_001, "+12125550101", "I think so, haven't confirmed yet. You?", now - 10 * day + hr, true, tx),
                msg(90_017, 9_001, "+12125550101", "Yeah I'll be there! Can you give me a ride?", now - 10 * day + 2 * hr, false, rx),
                msg(90_018, 9_001, "+12125550101", "Of course, I'll pick you up at 7", now - 10 * day + 2 * hr + 15 * min, true, tx),
                msg(90_019, 9_001, "+12125550101", "You're the best ❤️", now - 10 * day + 2 * hr + 20 * min, false, rx),
                msg(90_020, 9_001, "+12125550101", "That party was so fun! Emma's new place is gorgeous", now - 5 * day, false, rx),
                msg(90_021, 9_001, "+12125550101", "Right?? The rooftop view was insane", now - 5 * day + 30 * min, true, tx),
                msg(90_022, 9_001, "+12125550101", "We need to do that more often", now - 5 * day + 45 * min, false, rx),
                msg(90_023, 9_001, "+12125550101", "100%. Also sorry for keeping you out so late haha", now - 5 * day + hr, true, tx),
                msg(90_024, 9_001, "+12125550101", "Are you kidding, best night I've had in months", now - 5 * day + hr + 10 * min, false, rx),
                msg(90_025, 9_001, "+12125550101", "Heads up — I'm making a big batch of soup, want some?", now - day + 10 * hr, false, rx),
                msg(90_026, 9_001, "+12125550101", "Wait seriously? Yes please 🙏", now - day + 10 * hr + 20 * min, true, tx),
                msg(90_027, 9_001, "+12125550101", "I'll drop some off around 6 if that works", now - day + 11 * hr, false, rx),
                msg(90_028, 9_001, "+12125550101", "Perfect, I'll be home. You're an angel", now - day + 11 * hr + 5 * min, true, tx),
                msg(90_029, 9_001, "+12125550101", "Hey, are you coming to the party tonight?", now - 2 * hr, false, rx),
                msg(90_030, 9_001, "+12125550101", "Yeah for sure! What time does it start?", now - 110 * min, true, tx),
                msg(90_031, 9_001, "+12125550101", "Around 8. Bring something to drink 🍻", now - 90 * min, false, rx),
                msg(90_032, 9_001, "+12125550101", "Will do! Should I bring anyone?", now - 60 * min, true, tx),
                msg(90_033, 9_001, "+12125550101", "The more the merrier!", now - 30 * min, false, rx),
                msg(90_034, 9_001, "+12125550101", "Sounds good, see you then!", now - 20 * min, true, tx),
                // ── Mike Chen (thread 9002) ──────────────────────────────────
                msg(90_100, 9_002, "+13105550102", "You coming to trivia Thursday?", now - 6 * day, false, rx),
                msg(90_101, 9_002, "+13105550102", "Wouldn't miss it, same team as last time?", now - 6 * day + hr, true, tx),
                msg(90_102, 9_002, "+13105550102", "Yeah, Ryan and Jess are in too", now - 6 * day + 2 * hr, false, rx),
                msg(90_103, 9_002, "+13105550102", "Nice, we're taking that trophy this time", now - 6 * day + 2 * hr + 10 * min, true, tx),
                msg(90_104, 9_002, "+13105550102", "Dude we were SO close to winning", now - 4 * day, true, tx),
                msg(90_105, 9_002, "+13105550102", "That last question killed us. Who knows that much about 80s cartoons", now - 4 * day + 30 * min, false, rx),
                msg(90_106, 9_002, "+13105550102", "Apparently the table next to us lol", now - 4 * day + 35 * min, true, tx),
                msg(90_107, 9_002, "+13105550102", "Rematch next week?", now - 4 * day + hr, false, rx),
                msg(90_108, 9_002, "+13105550102", "Absolutely. And I'm studying 80s cartoons", now - 4 * day + hr + 5 * min, true, tx),
                msg(90_109, 9_002, "+13105550102", "Did you watch the game last night?", now - day - 5 * hr, false, rx),
                msg(90_110, 9_002, "+13105550102", "Oh man yes, that last quarter was insane", now - day - 4 * hr, true, tx),
                msg(90_111, 9_002, "+13105550102", "I couldn't believe that call at the end", now - day - 4 * hr + 5 * min, false, rx),
                msg(90_112, 9_002, "+13105550102", "Haha yeah that was wild", now - day - 3 * hr, true, tx),
                // ── Mom (thread 9003) ────────────────────────────────────────
                msg(90_200, 9_003, "+14045550103", "Your cousin Jamie is getting married!", now - 14 * day, false, rx),
                msg(90_201, 9_003, "+14045550103", "No way, that's so exciting! When?", now - 14 * day + hr, true, tx),
                msg(90_202, 9_003, "+14045550103", "October! Save the date, the whole family will be there", now - 14 * day + 2 * hr, false, rx),
                msg(90_203, 9_003, "+14045550103", "Already on the calendar 🗓️", now - 14 * day + 3 * hr, true, tx),
                msg(90_204, 9_003, "+14045550103", "Did you get my voicemail?", now - 10 * day, false, rx),
                msg(90_205, 9_003, "+14045550103", "Sorry mom, was in a meeting. Calling you tonight!", now - 10 * day + 2 * hr, true, tx),
                msg(90_206, 9_003, "+14045550103", "Ok sweetheart, talk later ❤️", now - 10 * day + 2 * hr + 10 * min, false, rx),
                msg(90_207, 9_003, "+14045550103", "Dad wants to know if you're free for dinner Sunday", now - 7 * day, false, rx),
                msg(90_208, 9_003, "+14045550103", "Yes! What time should I come over?", now - 7 * day + hr, true, tx),
                msg(90_209, 9_003, "+14045550103", "Come at 5, I'm making your favorite 😊", now - 7 * day + 2 * hr, false, rx),
                msg(90_210, 9_003, "+14045550103", "Can't wait, see you Sunday!", now - 7 * day + 2 * hr + 5 * min, true, tx),
                msg(90_211, 9_003, "+14045550103", "Hi honey, are you eating well?", now - 4 * day, false, rx),
                msg(90_212, 9_003, "+14045550103", "Yes mom, I'm fine 😄", now - 4 * day + hr, true, tx),
                msg(90_213, 9_003, "+14045550103", "Good! Call me this weekend?", now - 3 * day + 2 * hr, false, rx),
                msg(90_214, 9_003, "+14045550103", "Of course, love you!", now - 3 * day + 3 * hr, true, tx),
                msg(90_215, 9_003, "+14045550103", "Love you too sweetheart ❤️", now - 3 * day, false, rx),
                // ── Alex Rivera (thread 9004) ────────────────────────────────
                msg(90_300, 9_004, "+15105550104", "Hey, want to grab lunch this week?", now - 8 * day, true, tx),
                msg(90_301, 9_004, "+15105550104", "I'd love to! Maybe Thursday?", now - 8 * day + hr, false, rx),
                msg(90_302, 9_004, "+15105550104", "Thursday works. How's noon?", now - 7 * day + hr, true, tx),
                msg(90_303, 9_004, "+15105550104", "Let me know when you're free", now - 7 * day, false, rx),
                // ── David Park (thread 9005) ─────────────────────────────────
                msg(90_400, 9_005, "+16175550105", "Hey man, I really appreciated your help moving", now - 15 * day, false, rx),
                msg(90_401, 9_005, "+16175550105", "Of course, anytime!", now - 14 * day + hr, true, tx),
                msg(90_402, 9_005, "+16175550105", "Thanks for the help yesterday", now - 14 * day, false, rx),
            )
            messageRepository.insertAll(msgs)
            _feedback.value = "Sample data loaded — ${sampleThreads.size} threads, ${msgs.size} messages"
        }
    }

    // ── Sample data helpers ──────────────────────────────────────────────────

    // Sample thread IDs are fixed at 9001–9005 by loadSampleData().
    private val SAMPLE_THREAD_IDS = listOf(9_001L, 9_002L, 9_003L, 9_004L, 9_005L)

    fun clearSampleData() {
        viewModelScope.launch {
            SAMPLE_THREAD_IDS.forEach { id ->
                messageRepository.deleteByThread(id)
                threadRepository.getById(id)?.let { threadRepository.delete(it) }
            }
            _feedback.value = "Sample data removed"
        }
    }

    // ── Clear all data ────────────────────────────────────────────────────────

    fun clearAllData() {
        viewModelScope.launch {
            messageRepository.deleteAll()   // also clears reactions
            threadRepository.deleteAll()
            _feedback.value = "All data cleared"
        }
    }

    // ── SMS sync helpers ──────────────────────────────────────────────────────

    fun resetSyncFlag() {
        context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)
            .edit().remove("first_sync_completed").apply()
        _feedback.value = "Sync flag reset — sync will run on next trigger"
    }

    fun triggerSync() {
        Log.i("SyncTrigger", "DevOptionsViewModel.triggerSync — enqueuing REPLACE")
        context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)
            .edit().remove("first_sync_completed").apply()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FirstLaunchSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            FirstLaunchSyncWorker.buildRequest()
        )
        _feedback.value = "Sync worker enqueued"
    }

    // Wipes all locally cached messages and threads, then re-runs the full historical
    // sync so newly supported MMS attachments (images, audio, video) are imported.
    // SAFE: never touches content://sms — only deletes from Room.
    fun wipeAndResync() {
        viewModelScope.launch {
            Log.i("SyncTrigger", "DevOptionsViewModel.wipeAndResync — wiping DB then enqueuing REPLACE")
            messageRepository.deleteAll()
            threadRepository.deleteAll()
            context.getSharedPreferences("postmark_prefs", Context.MODE_PRIVATE)
                .edit().remove("first_sync_completed").apply()
            WorkManager.getInstance(context).enqueueUniqueWork(
                FirstLaunchSyncWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                FirstLaunchSyncWorker.buildRequest()
            )
            _feedback.value = "DB wiped — full re-import started"
        }
    }

    // ── Reprocess reactions (DEBUG) ────────────────────────────────────────
    // Scans every stored message, converts any reaction fallback to a Reaction entity
    // (deduped), and deletes the original message so it no longer shows as a bubble.

    fun reprocessReactions() {
        viewModelScope.launch {
            _isReprocessing.value = true
            try {
                val allMessages = messageRepository.getAll()
                val byThread = allMessages.groupBy { it.threadId }
                var inserted = 0
                var removed = 0
                val toDelete = mutableListOf<Long>()

                byThread.forEach { (_, msgs) ->
                    msgs.forEach { msg ->
                        val parsed = reactionParser.parse(msg.body) ?: return@forEach
                        if (!parsed.isRemoval) {
                            val reaction = reactionParser.processIncomingMessage(msg, msgs, msg.address)
                            if (reaction != null && !messageRepository.reactionExists(
                                    reaction.messageId, reaction.senderAddress, reaction.emoji)) {
                                messageRepository.insertReaction(reaction)
                                inserted++
                            }
                        }
                        toDelete += msg.id
                        removed++
                    }
                }

                toDelete.forEach { messageRepository.deleteById(it) }
                statsUpdater.recomputeAll()
                _feedback.value = "Reactions reprocessed: $inserted inserted, $removed fallbacks removed"
            } finally {
                _isReprocessing.value = false
            }
        }
    }

    private fun msg(id: Long, threadId: Long, address: String, body: String, ts: Long, isSent: Boolean, type: Int) =
        Message(id, threadId, address, body, ts, isSent, type)
}
