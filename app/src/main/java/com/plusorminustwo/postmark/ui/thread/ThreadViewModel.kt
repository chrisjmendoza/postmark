package com.plusorminustwo.postmark.ui.thread

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.os.SystemClock
import android.provider.BlockedNumberContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.contacts.lookupContactName
import com.plusorminustwo.postmark.util.isDefaultSmsApp
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_QUEUED
import com.plusorminustwo.postmark.data.preferences.AppAccentPreferenceRepository
import com.plusorminustwo.postmark.data.preferences.BubbleFontScaleRepository
import com.plusorminustwo.postmark.data.preferences.BubbleStylePreferenceRepository
import com.plusorminustwo.postmark.data.preferences.ChatBackgroundPreferenceRepository
import com.plusorminustwo.postmark.data.preferences.DraftRepository
import com.plusorminustwo.postmark.data.preferences.GestureHintsRepository
import com.plusorminustwo.postmark.data.preferences.SaveNumberPromptRepository
import com.plusorminustwo.postmark.data.preferences.SpamSuspicionRepository
import com.plusorminustwo.postmark.data.preferences.TimestampPreferenceRepository
import com.plusorminustwo.postmark.data.reaction.ReactionFallbackParser
import com.plusorminustwo.postmark.data.repository.BlockedNumbersRepository
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ScheduledMessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.RESTORED_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.recipientsFor
import com.plusorminustwo.postmark.domain.reaction.composeReactionFallback
import com.plusorminustwo.postmark.domain.reaction.reactionFallbackRoundTrips
import com.plusorminustwo.postmark.domain.model.decodeAttachmentsJson
import com.plusorminustwo.postmark.domain.model.encodeAttachmentsJson
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.contacts.shouldShowSaveNumberPrompt
import com.plusorminustwo.postmark.domain.spam.looksLikeSpam
import com.plusorminustwo.postmark.domain.spam.shouldShowSpamBanner
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.voicememo.VoiceMemoEffect
import com.plusorminustwo.postmark.domain.voicememo.VoiceMemoEvent
import com.plusorminustwo.postmark.domain.voicememo.VoiceMemoPhase
import com.plusorminustwo.postmark.domain.voicememo.VOICE_WAVEFORM_BUCKETS
import com.plusorminustwo.postmark.domain.voicememo.isMemoKeepable
import com.plusorminustwo.postmark.domain.voicememo.normalizedRecordingLevel
import com.plusorminustwo.postmark.domain.voicememo.resampleAmplitudes
import com.plusorminustwo.postmark.domain.voicememo.voiceMemoTransition
import com.plusorminustwo.postmark.domain.customization.BubbleStylePreference
import com.plusorminustwo.postmark.domain.customization.TimestampPreference
import com.plusorminustwo.postmark.service.audio.VoiceMemoRecorder
import com.plusorminustwo.postmark.service.customization.ChatBackgroundImageStore
import com.plusorminustwo.postmark.service.sms.ActiveThreadTracker
import com.plusorminustwo.postmark.service.sms.MmsManagerWrapper
import com.plusorminustwo.postmark.service.reminder.MessageReminderWorker
import com.plusorminustwo.postmark.service.scheduled.ScheduledSendWorker
import com.plusorminustwo.postmark.service.sms.MmsSentReceiver
import com.plusorminustwo.postmark.service.sms.SendQueueWorker
import com.plusorminustwo.postmark.service.sms.SmsManagerWrapper
import com.plusorminustwo.postmark.service.sms.SmsSendDispatcher
import com.plusorminustwo.postmark.domain.scheduled.ScheduledMessage
import com.plusorminustwo.postmark.domain.scheduled.ScheduleValidation
import com.plusorminustwo.postmark.domain.scheduled.validateScheduledSend
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * All UI state for the thread screen, collected by [ThreadScreen] and rendered by [ThreadContent].
 *
 * @property thread                  The current [Thread] metadata (display name, muted, pinned, etc.).
 * @property messages                All messages in this thread, newest last (LazyColumn renders reversed).
 * @property selectedMessageIds      IDs of messages currently checked in multi-select mode.
 * @property isSelectionMode         True when the screen is in multi-select mode.
 * @property selectionScope          Scope of the current selection (MESSAGES or ALL).
 * @property replyText               Current text in the reply bar input field.
 * @property isSending               True while an outgoing message is being handed to telephony.
 * @property showDefaultSmsDialog    True when the "Set as default SMS app" dialog should be shown.
 * @property expandedTimestampIds    IDs of bubbles whose timestamps are currently revealed (ON_TAP mode).
 * @property reactionPickerMessageId ID of the message whose action bar / emoji picker is open, or null.
 * @property reactionPickerBubbleY   Root-Y of the long-pressed bubble, used to position the emoji popup.
 * @property highlightedMessageId    ID of the message highlighted after a search-jump; clears after 2 s.
 */
data class ThreadUiState(
    val thread: Thread? = null,
    val messages: List<Message> = emptyList(),
    val selectedMessageIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    val selectionScope: SelectionScope = SelectionScope.MESSAGES,
    val replyText: String = "",
    val isSending: Boolean = false,
    val showDefaultSmsDialog: Boolean = false,
    val expandedTimestampIds: Set<Long> = emptySet(),
    val reactionPickerMessageId: Long? = null,
    val reactionPickerBubbleY: Float = 0f,
    // Root-Y of the long-pressed bubble's TOP edge — lets the popup flip above the bubble
    // when placing it below would land under the navigation bar (see reactionPopupTopPx).
    val reactionPickerBubbleTopY: Float = 0f,
    val highlightedMessageId: Long? = null,
    // Pending outgoing MMS attachments (URI + MIME type each). Empty when composing plain SMS.
    val pendingAttachments: List<MessageAttachment> = emptyList(),
    // ID of the message being quoted via swipe-to-reply; null = no active quote.
    val replyingToId: Long? = null,
    // Pre-computed flat render list + index maps — derived from messages, computed in the
    // ViewModel so the composable never re-derives them on the main thread.
    val renderState: ThreadRenderState = ThreadRenderState(),
    // Every image attachment across the whole thread, in chronological (message) order.
    // Backs the full-screen image viewer so swiping moves to the next/previous image
    // across message boundaries, not just within the tapped message's own attachments.
    val threadImages: List<ThreadImageRef> = emptyList()
)

/**
 * ViewModel for the thread (conversation) screen.
 *
 * Owns all mutable state for message selection, the reply bar, the emoji reaction picker,
 * and the timestamp display preference. Delegates persistence to [ThreadRepository] and
 * [MessageRepository], and SMS sending to [SmsManagerWrapper].
 *
 * The public [uiState] StateFlow is a flat [ThreadUiState] snapshot assembled by combining
 * several internal flows so that [ThreadContent] can be a fully stateless composable.
 */
@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val scheduledMessageRepository: ScheduledMessageRepository,
    private val blockedNumbersRepository: BlockedNumbersRepository,
    private val smsManagerWrapper: SmsManagerWrapper,
    private val smsSendDispatcher: SmsSendDispatcher,
    private val mmsManagerWrapper: MmsManagerWrapper,
    private val reactionParser: ReactionFallbackParser,
    private val timestampPrefRepo: TimestampPreferenceRepository,
    private val fontScaleRepo: BubbleFontScaleRepository,
    private val bubbleStyleRepo: BubbleStylePreferenceRepository,
    private val chatBackgroundPrefRepo: ChatBackgroundPreferenceRepository,
    private val chatBackgroundImageStore: ChatBackgroundImageStore,
    private val appAccentPrefRepo: AppAccentPreferenceRepository,
    private val draftRepository: DraftRepository,
    private val spamSuspicionRepository: SpamSuspicionRepository,
    private val saveNumberPromptRepository: SaveNumberPromptRepository,
    private val gestureHintsRepo: GestureHintsRepository,
    private val voiceMemoRecorder: VoiceMemoRecorder,
    private val activeThreadTracker: ActiveThreadTracker
) : ViewModel() {

    private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

    /**
     * Registers this thread as the on-screen conversation so incoming SMS/MMS for it skip
     * the notification banner. Driven by ThreadScreen's ON_RESUME/ON_PAUSE so the tracker
     * follows what the user is actually looking at, not merely which ViewModel exists.
     */
    fun onScreenResumed() = activeThreadTracker.setActive(threadId)

    /** Clears this thread's active-registration on ON_PAUSE (no-op if a newer screen already
     *  took over — [ActiveThreadTracker.clearActive] only clears on a matching id). */
    fun onScreenPaused() = activeThreadTracker.clearActive(threadId)

    /**
     * Whether this SIM's carrier permits group MMS — read once from SmsManager carrier
     * config ([SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED], default true). When false, the
     * ReplyBar keeps the group banner (with carrier-specific copy) and group threads fall
     * back to today's 1:1 send. This is a rare carrier state; we do NOT build a speculative
     * per-recipient broadcast mode for it (GROUP_MESSAGING_SPEC §2.1).
     */
    val groupSendSupported: Boolean = try {
        val subId = SmsManager.getDefaultSmsSubscriptionId()
        val sm = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        else context.getSystemService(SmsManager::class.java)
        sm.carrierConfigValues.getBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, true)
    } catch (_: Exception) { true }

    /** Recipients for an outgoing send: the full roster for a group thread, unless the
     *  carrier disables group MMS — then it collapses to the single 1:1 address. */
    private fun sendRecipientsFor(thread: Thread): List<String> =
        if (groupSendSupported) recipientsFor(thread) else listOf(thread.address)

    init {
        // Mark all messages in this thread as read as soon as the thread is opened.
        viewModelScope.launch { messageRepository.markAllRead(threadId) }
    }

    private val _selectionState  = MutableStateFlow(emptySet<Long>())
    private val _isSelectionMode = MutableStateFlow(false)
    private val _selectionScope  = MutableStateFlow(SelectionScope.MESSAGES)
    // Reply text seeds from SavedStateHandle (process death while this screen is
    // still on the back stack) and falls back to the persisted per-thread draft,
    // so a half-typed message survives leaving the chat entirely.
    private val _replyText       = MutableStateFlow(
        savedStateHandle.get<String>(DRAFT_TEXT_KEY) ?: draftRepository.draftFor(threadId) ?: ""
    )

    init {
        // Draft persistence, debounced like BubbleFontScaleRepository: an apply()
        // per keystroke floods QueuedWork (flushed synchronously at Activity.onStop).
        // drop(1) skips re-persisting the value just restored above; send clears the
        // text, which persists as a delete. The trailing ≤400 ms of typing can be
        // lost to process death — same accepted trade-off as the font scale.
        viewModelScope.launch {
            _replyText.drop(1).collectLatest { text ->
                delay(400)
                draftRepository.setDraft(threadId, text)
            }
        }
    }
    private val _isSending       = MutableStateFlow(false)
    private val _showDefaultSmsDialog     = MutableStateFlow(false)
    private val _expandedTimestampIds     = MutableStateFlow(emptySet<Long>())
    private val _reactionPickerMessageId  = MutableStateFlow<Long?>(null)
    private val _reactionPickerBubbleY     = MutableStateFlow(0f)
    private val _reactionPickerBubbleTopY  = MutableStateFlow(0f)
    private val _highlightedMessageId     = MutableStateFlow<Long?>(null)
    // Pending outgoing MMS attachments; empty when composing SMS. Persisted to
    // SavedStateHandle as the same JSON encoding used by the Room column.
    private val _pendingAttachments = MutableStateFlow(
        decodeAttachmentsJson(savedStateHandle.get<String>(DRAFT_ATTACHMENTS_KEY))
    )
    // ID of the message the user is replying to (swipe-to-reply); null = no active quote.
    private val _replyingToId         = MutableStateFlow<Long?>(null)

    /* Fires once per send carrying the optimistic message's tempId, so the UI can
     * wait for that row to reach the composed list before scrolling to the bottom
     * (see ThreadScrollToBottomEffect). extraBufferCapacity=1 means the emit never
     * suspends even if the collector hasn't consumed yet. */
    private val _scrollToBottomEvent = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val scrollToBottomEvent: SharedFlow<Long> = _scrollToBottomEvent.asSharedFlow()

    /** Fires when [onAttachmentsSelected] drops a video for exceeding
     *  [MmsManagerWrapper.MAX_VIDEO_DURATION_MS] — the UI shows this as a Snackbar. */
    private val _attachmentRejectedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val attachmentRejectedEvent: SharedFlow<String> = _attachmentRejectedEvent.asSharedFlow()

    /** Fires the FIRST time the user ever toggles a reaction, carrying [REACTIONS_LOCAL_NOTICE].
     *  Reactions are local-only annotations that are never transmitted, but the pill UI reads
     *  as two-way — this one-shot Snackbar sets that expectation honestly, then never again. */
    private val _reactionsLocalNoticeEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val reactionsLocalNoticeEvent: SharedFlow<String> = _reactionsLocalNoticeEvent.asSharedFlow()

    /** One-time thread gesture-tips card visibility (un-dismissed = true). The card also
     *  gates on message count in the UI via [shouldShowThreadTips]. */
    val threadTipsDismissed: StateFlow<Boolean> = gestureHintsRepo.threadTipsDismissed

    /** Dismisses the thread gesture-tips card permanently. */
    fun dismissThreadTips() = gestureHintsRepo.markThreadTipsDismissed()

    val timestampPreference: StateFlow<TimestampPreference> = timestampPrefRepo.preference

    /** Global default chat-background id (null = none); overridden per-thread by
     *  [com.plusorminustwo.postmark.domain.model.Thread.chatBackgroundId] when set. */
    val globalChatBackgroundId: StateFlow<String?> = chatBackgroundPrefRepo.backgroundId

    /** Resolved file for a custom-image chat-background [id], or null if it's not an image
     *  id or the file is missing (e.g. an id restored on a device without the bytes).
     *  Exposed so [ThreadContent] can render the image without injecting the store. */
    fun chatBackgroundImageFile(id: String): java.io.File? = chatBackgroundImageStore.fileFor(id)

    /** Global app accent (packed ARGB); null = Postmark's own brand blue. Read here
     *  (not just by [com.plusorminustwo.postmark.ui.theme.PostmarkTheme]) so an
     *  un-customized thread's sent-bubble default TEXT color can follow the accent's
     *  `onPrimaryContainer` instead of the ambient content color once its CONTAINER
     *  is the vivid accent — see the `bubbleAccentColors` derivation in ThreadContent. */
    val appAccentArgb: StateFlow<Int?> = appAccentPrefRepo.accentArgb

    /**
     * Everything derived from the raw message list — built once per Room emission on
     * [Dispatchers.Default], then shared by [uiState] and [activeDates]. The uiState
     * combine re-fires for every reply-bar keystroke and selection tap; this flow
     * re-fires only when the messages table actually changes, so the O(n) clustering,
     * date formatting, and image indexing never run on the per-keystroke path and
     * never on the main thread. The flowOn also pulls the repository's reactions-join
     * and per-row entity mapping (upstream of it in this chain) off Main.
     */
    private data class RenderPayload(
        val messages: List<Message>,
        val renderState: ThreadRenderState,
        val threadImages: List<ThreadImageRef>,
        val activeDates: Set<LocalDate>
    )

    private val renderPayload: Flow<RenderPayload> = messageRepository
        .observeByThread(threadId)
        // Room invalidation is table-granular: a write to ANY thread re-runs this
        // query, usually with an equal result for the open thread. Compare lists
        // here (cheap, and on Default thanks to the flowOn below) so the expensive
        // rebuild is skipped; StateFlow conflation downstream can't help because it
        // compares AFTER the map has already done the work.
        .distinctUntilChanged()
        .map { messages ->
            RenderPayload(
                messages = messages,
                renderState = buildRenderState(messages),
                threadImages = buildThreadImages(messages),
                activeDates = messages.mapTo(mutableSetOf()) { msg ->
                    Instant.ofEpochMilli(msg.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                }
            )
        }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val activeDates: StateFlow<Set<LocalDate>> = renderPayload
        .map { it.activeDates }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val quickReactionEmojis: StateFlow<List<String>> = messageRepository
        .observeTopUserEmojis()
        .map { topEmojis -> buildQuickEmojiList(topEmojis) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_QUICK_EMOJIS)

    /** This thread's pinned messages, oldest first (Discord-style) — backs the Pinned
     *  messages panel opened from the ⋮ overflow menu. Collected independently of the big
     *  uiState combine (which is at its argument limit); the panel is a self-contained sheet. */
    val pinnedMessages: StateFlow<List<Message>> = messageRepository
        .observePinnedMessages(threadId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** This thread's flagged (reminder-set) messages, soonest reminder first — backs the
     *  Reminders panel opened from the ⋮ overflow menu. Mirrors [pinnedMessages]; collected
     *  independently of the big uiState combine (which is at its argument limit). */
    val flaggedMessages: StateFlow<List<Message>> = messageRepository
        .observeFlaggedMessages(threadId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** This thread's parked "Schedule send" texts, soonest first — backs the scheduled bubble
     *  section rendered at the bottom of the thread. Observed off the separate scheduled_messages
     *  table so a bubble appears the moment one is created and vanishes the moment its worker
     *  fires (or the user cancels/sends-now/edits). Collected independently of the big uiState
     *  combine (which is at its argument limit). */
    val scheduledMessages: StateFlow<List<ScheduledMessage>> = scheduledMessageRepository
        .observeByThread(threadId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * address → contact display name for this thread's roster, resolved once per
     * roster change on IO (each entry is a ContactsContract query). Empty for 1:1
     * threads, so the UI can use `isNotEmpty()` as the "group thread — show
     * per-bubble sender labels" signal.
     */
    val participantNames: StateFlow<Map<String, String>> = threadRepository
        .observeById(threadId)
        .map { it?.participants.orEmpty() }
        .distinctUntilChanged()
        .map { roster ->
            if (roster.size <= 1) emptyMap()
            else roster.associateWith { addr -> context.lookupContactName(addr) ?: formatPhoneNumber(addr) }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ── Suspected-spam banner ───────────────────────────────────────────────────
    // Conservative and display-time only: suspicion is recomputed from the thread's first
    // inbound message and NEVER auto-moves the thread to the Spam folder (see domain/spam).
    // No schema change — only the user's per-thread dismissal is persisted.

    /** True once the user dismisses the banner for this thread; seeded from prefs. */
    private val _spamSuspicionDismissed = MutableStateFlow(spamSuspicionRepository.isDismissed(threadId))

    /** This thread's address resolved to a contact display name (null = no matching contact) —
     *  looked up off-main on each address change. Feeds both the spam banner (known contact
     *  suppresses it outright) and the save-number-prompt banner below. */
    private val senderContactName: StateFlow<String?> = threadRepository
        .observeById(threadId)
        .map { it?.address.orEmpty() }
        .distinctUntilChanged()
        .map { address -> address.ifEmpty { null }?.let { context.lookupContactName(it) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether to show the "Looks like spam?" banner. Suspect when [looksLikeSpam] holds for
     * the thread's FIRST RECEIVED message — the cold inbound that would define spam; a thread
     * the user opened by texting out first is judged on the other party's earliest reply, not
     * the user's own outgoing text. Hidden once the thread is actually marked spam or the
     * banner has been dismissed.
     */
    val spamBannerVisible: StateFlow<Boolean> = combine(
        threadRepository.observeById(threadId),
        renderPayload,
        senderContactName,
        _spamSuspicionDismissed
    ) { thread, payload, contactName, dismissed ->
        val isSpam  = thread?.isSpam ?: false
        val isGroup = (thread?.participants?.size ?: 0) > 1
        val firstInbound = payload.messages.firstOrNull { !it.isSent }?.body
        val suspect = firstInbound != null && looksLikeSpam(firstInbound, !contactName.isNullOrBlank(), isGroup)
        shouldShowSpamBanner(suspect, isSpam, dismissed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── "Add to contacts?" banner ───────────────────────────────────────────────
    // Same shape as the spam banner above: display-only, recomputed live, only the user's
    // dismissal persisted. The spam banner wins when both would otherwise apply — never
    // shown together (see domain/contacts/SaveNumberPrompt.kt).

    /** True once the user dismisses the banner for this thread; seeded from prefs. */
    private val _saveNumberPromptDismissed =
        MutableStateFlow(saveNumberPromptRepository.isDismissed(threadId))

    /** Whether to show the "Add to contacts?" banner — see [shouldShowSaveNumberPrompt]. */
    val saveNumberPromptVisible: StateFlow<Boolean> = combine(
        threadRepository.observeById(threadId),
        senderContactName,
        _saveNumberPromptDismissed,
        spamBannerVisible
    ) { thread, contactName, dismissed, spamVisible ->
        val isGroup = (thread?.participants?.size ?: 0) > 1
        shouldShowSaveNumberPrompt(isGroup, contactName, thread?.address.orEmpty(), dismissed, spamVisible)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Named holder for the action-related sub-state to stay within combine's 5-arg limit.
    private data class InnerState(
        val replyText: String,
        val isSending: Boolean,
        val showDefaultSmsDialog: Boolean,
        val expandedTimestampIds: Set<Long>,
        val selectionScope: SelectionScope,
        val reactionPickerMessageId: Long?,
        val reactionPickerBubbleY: Float,
        val reactionPickerBubbleTopY: Float,
        val highlightedMessageId: Long?,
        val pendingAttachments: List<MessageAttachment>,
        // ID of the message being quoted via swipe-to-reply; null = no active quote.
        val replyingToId: Long?
    )

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ThreadUiState> = combine(
        threadRepository.observeById(threadId),
        renderPayload,
        _selectionState,
        _isSelectionMode,
        combine(
            _replyText,
            _isSending,
            _showDefaultSmsDialog,
            _expandedTimestampIds,
            _selectionScope,
            _reactionPickerMessageId,
            _reactionPickerBubbleY,
            _reactionPickerBubbleTopY,
            _highlightedMessageId,
            _pendingAttachments,
            _replyingToId
        ) { arr ->
            InnerState(
                replyText                 = arr[0] as String,
                isSending                 = arr[1] as Boolean,
                showDefaultSmsDialog      = arr[2] as Boolean,
                expandedTimestampIds      = arr[3] as Set<Long>,
                selectionScope            = arr[4] as SelectionScope,
                reactionPickerMessageId   = arr[5] as Long?,
                reactionPickerBubbleY     = arr[6] as Float,
                reactionPickerBubbleTopY  = arr[7] as Float,
                highlightedMessageId      = arr[8] as Long?,
                pendingAttachments        = arr[9] as List<MessageAttachment>,
                replyingToId              = arr[10] as Long?
            )
        }
    ) { thread, payload, selected, selectionMode, inner ->
        ThreadUiState(
            thread = thread,
            messages = payload.messages,
            renderState = payload.renderState,
            threadImages = payload.threadImages,
            selectedMessageIds = selected,
            isSelectionMode = selectionMode,
            selectionScope = inner.selectionScope,
            replyText = inner.replyText,
            isSending = inner.isSending,
            showDefaultSmsDialog = inner.showDefaultSmsDialog,
            expandedTimestampIds = inner.expandedTimestampIds,
            reactionPickerMessageId  = inner.reactionPickerMessageId,
            reactionPickerBubbleY    = inner.reactionPickerBubbleY,
            reactionPickerBubbleTopY = inner.reactionPickerBubbleTopY,
            highlightedMessageId     = inner.highlightedMessageId,
            pendingAttachments      = inner.pendingAttachments,
            replyingToId            = inner.replyingToId
        )
    }
        // NOTE: this combine deliberately stays on Main — selection taps must apply
        // synchronously (July 12: a flowOn(Default) here froze selection on device;
        // root cause was a SimpleDateFormat data race, fixed July 15 via immutable
        // DateTimeFormatter). The heavy work now runs upstream in [renderPayload] on
        // Default, so this transform is pure field assembly with nothing worth a hop.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())

    // ── Selection ─────────────────────────────────────────────────────────────

    /** Standard toolbar entry — no message pre-selected. */
    fun enterSelectionMode() {
        _isSelectionMode.value = true
        _selectionScope.value  = SelectionScope.MESSAGES
    }

    /** Long-press entry — selects [messageId] immediately. */
    fun enterSelectionModeWithMessage(messageId: Long) {
        _isSelectionMode.value = true
        _selectionScope.value  = SelectionScope.MESSAGES
        _selectionState.update { it + messageId }
    }

    // Bridges the five separate selection-flow values to/from the pure [SelectionSnapshot]
    // reducer so every long-press-flow transition has one source of truth.
    private fun selectionSnapshot() = SelectionSnapshot(
        pickerMessageId = _reactionPickerMessageId.value,
        bubbleY         = _reactionPickerBubbleY.value,
        bubbleTopY      = _reactionPickerBubbleTopY.value,
        isSelectionMode = _isSelectionMode.value,
        selection       = _selectionState.value,
        scope           = _selectionScope.value
    )

    private fun applySelectionSnapshot(s: SelectionSnapshot) {
        _reactionPickerMessageId.value  = s.pickerMessageId
        _reactionPickerBubbleY.value    = s.bubbleY
        _reactionPickerBubbleTopY.value = s.bubbleTopY
        _isSelectionMode.value          = s.isSelectionMode
        _selectionState.value           = s.selection
        _selectionScope.value           = s.scope
    }

    fun exitSelectionMode() = applySelectionSnapshot(exitSelection())

    fun toggleSelection(messageId: Long) {
        _selectionState.update { cur ->
            if (messageId in cur) cur - messageId else cur + messageId
        }
    }

    /**
     * Toggles all [ids] as a unit:
     *   • if every id is already selected → deselect all of them
     *   • otherwise → select all of them
     */
    fun toggleMessageIds(ids: List<Long>) {
        val idSet = ids.toSet()
        _selectionState.update { cur ->
            if (idSet.all { it in cur }) cur - idSet else cur + idSet
        }
    }

    /**
     * Changes the active selection scope.
     * Switching to [SelectionScope.ALL] immediately selects every loaded message;
     * tapping ALL again when everything is already selected clears the selection
     * and reverts to MESSAGES scope.
     */
    fun setSelectionScope(scope: SelectionScope) {
        if (scope == SelectionScope.ALL) {
            val allIds = uiState.value.messages.map { it.id }.toSet()
            if (_selectionState.value == allIds) {
                _selectionState.value = emptySet()
                _selectionScope.value = SelectionScope.MESSAGES
            } else {
                _selectionState.value = allIds
                _selectionScope.value = SelectionScope.ALL
            }
        } else {
            _selectionScope.value = scope
        }
    }

    fun clearSelection() = exitSelectionMode()

    // ── Reactions ─────────────────────────────────────────────────────────────

    /**
     * Long-press entry point. When not already in selection mode this opens the anchored
     * emoji popup AND enters selection mode with [messageId] as the sole selection. When
     * selection mode is already live a long-press just toggles that message (like a tap),
     * without opening a popup or clobbering the running multi-selection.
     */
    fun showReactionPicker(messageId: Long, bubbleTopY: Float, bubbleBottomY: Float) =
        applySelectionSnapshot(longPress(selectionSnapshot(), messageId, bubbleTopY, bubbleBottomY))

    /** Dismisses the popup only — selection mode and the current selection are preserved. */
    fun dismissReactionPicker() =
        applySelectionSnapshot(dismissPicker(selectionSnapshot()))

    fun toggleStarred(messageId: Long) {
        viewModelScope.launch {
            val message = messageRepository.getById(messageId) ?: return@launch
            messageRepository.updateStarred(messageId, !message.isStarred)
        }
    }

    /** Pin/unpin a single message (Discord-style). Mirrors [toggleStarred]. */
    fun togglePinnedMessage(messageId: Long) {
        viewModelScope.launch {
            val message = messageRepository.getById(messageId) ?: return@launch
            messageRepository.updatePinned(messageId, !message.isPinned)
        }
    }

    /**
     * Sets or clears a message's reply reminder. [remindAt] non-null flags the message and
     * schedules a WorkManager job to fire at that time; null unflags it and cancels the job.
     * The DB write is the source of truth (drives the 🔖 indicator and the Reminders list);
     * the worker re-checks remindAt on fire, so an unflag that races a firing job is safe.
     */
    fun setReminder(messageId: Long, remindAt: Long?) {
        viewModelScope.launch {
            messageRepository.updateRemindAt(messageId, remindAt)
            if (remindAt != null) {
                MessageReminderWorker.schedule(context, messageId, remindAt)
            } else {
                MessageReminderWorker.cancel(context, messageId)
            }
        }
    }

    /**
     * Permanently deletes a message — both the Room row and, for a real (non-optimistic,
     * `id > 0`) row, the underlying system `content://sms` or `content://mms` row. Requires
     * being the default SMS app (system providers reject writes from non-default apps);
     * sets [ThreadUiState.showDefaultSmsDialog] (the same dialog [sendMessage] uses) instead
     * of failing silently if not.
     *
     * The caller is expected to confirm with the user first — this performs the delete
     * immediately and unconditionally once called.
     */
    fun deleteMessage(messageId: Long) {
        if (!context.isDefaultSmsApp()) {
            _showDefaultSmsDialog.value = true
            return
        }
        viewModelScope.launch {
            deleteMessageRow(messageId)
        }
    }

    /**
     * Bulk delete for the selection bar — deletes every message in [ids] sequentially in one
     * coroutine, reusing the same per-id logic as [deleteMessage]. The default-SMS-app guard is
     * applied once up front. These provider deletes are legitimate: this is invoked only from an
     * explicit user delete action (the sanctioned case in CLAUDE.md), never from sync/migration.
     */
    fun deleteMessages(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        if (!context.isDefaultSmsApp()) {
            _showDefaultSmsDialog.value = true
            return
        }
        viewModelScope.launch {
            ids.forEach { deleteMessageRow(it) }
        }
    }

    /**
     * Deletes a single message — both the Room row and, for a real (non-optimistic, `id > 0`)
     * row, the underlying system `content://sms`/`content://mms` row. Assumes the default-SMS-app
     * check has already passed (callers guard once). Suspends; no scope of its own so it can be
     * looped from [deleteMessages].
     */
    private suspend fun deleteMessageRow(messageId: Long) {
        val message = messageRepository.getById(messageId) ?: return
        // Negative IDs are optimistic (not-yet-synced) rows — no system row exists yet.
        // Restored rows (id >= RESTORED_ID_OFFSET) exist only in Room by construction,
        // so there is no provider row to delete for them either.
        if (message.id in 1 until RESTORED_ID_OFFSET) {
            val uri = if (message.isMms) {
                Uri.parse("content://mms/${message.id - MMS_ID_OFFSET}")
            } else {
                Uri.parse("content://sms/${message.id}")
            }
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                    // System provider delete failed (e.g. race with sync) — still remove
                    // the local row below so the UI doesn't show a message the user just
                    // asked to delete; a future resync can't resurrect a row that no
                    // longer exists in Room even if the system row somehow survived.
                }
            }
        }
        messageRepository.deleteById(messageId)
        // Remove the outgoing-MMS media this message cached in filesDir so it doesn't
        // leak (no-op for SMS / received MMS — those carry no mms_attach_* cache files).
        withContext(Dispatchers.IO) {
            MmsManagerWrapper.deleteAttachmentCacheFiles(context, message.attachments)
        }
    }

    fun toggleReaction(messageId: Long, emoji: String) {
        viewModelScope.launch {
            val message = uiState.value.messages.find { it.id == messageId } ?: return@launch
            val myReaction = message.reactions.find {
                it.senderAddress == SELF_ADDRESS && it.emoji == emoji
            }
            val isRemoval = myReaction != null
            // The local pill toggles immediately and unconditionally — sending the fallback
            // is a best-effort side channel, never a precondition for the on-device state.
            // Sync's reactionExists dedupe stops the re-imported sent fallback double-inserting.
            if (isRemoval) {
                messageRepository.deleteReaction(messageId, SELF_ADDRESS, emoji)
            } else {
                messageRepository.insertReaction(
                    Reaction(
                        id = 0,
                        messageId = messageId,
                        senderAddress = SELF_ADDRESS,
                        emoji = emoji,
                        timestamp = System.currentTimeMillis(),
                        rawText = ""
                    )
                )
            }
            // Reacting completes the user's intent: close the popup AND leave selection mode.
            exitSelectionMode()

            // Also SEND the Android-style fallback SMS so the recipient's app shows it
            // (v1: 1:1 text targets only). Removal toggles send the "removed" variant.
            val sent = maybeSendReactionFallback(uiState.value.thread, message, emoji, isRemoval)

            /* The "reactions stay on your phone" notice now fires ONLY when a toggle-ON stays
             * local-only (media/group/round-trip-unsafe target) — a sent reaction reaches the
             * other person, and a removal is never a surprise. Shown once, then never again;
             * the pref key was bumped so the reworded copy surfaces once for existing users. */
            if (!isRemoval && !sent && !gestureHintsRepo.reactionsLocalNoticeShown.value) {
                gestureHintsRepo.markReactionsLocalNoticeShown()
                _reactionsLocalNoticeEvent.tryEmit(REACTIONS_LOCAL_NOTICE)
            }
        }
    }

    /**
     * Best-effort send of the Android-Messages-style reaction fallback SMS for reacting
     * [emoji] to [target] in [thread]. Returns true only when a fallback was actually
     * dispatched; false (local-only) for every unsupported or unsafe case.
     *
     * v1 scope (decisions in docs/CHANGELOG.md): 1:1 threads with a non-blank text target
     * only — media-only targets and group threads stay local-only. Every candidate send is
     * gated through OUR OWN parser+matcher ([reactionFallbackRoundTrips]): unless the
     * composed string parses back and resolves to exactly [target], it is left local-only
     * rather than risk landing a reaction on the wrong message on the recipient's phone.
     */
    private suspend fun maybeSendReactionFallback(
        thread: Thread?,
        target: Message,
        emoji: String,
        isRemoval: Boolean,
    ): Boolean {
        if (thread == null) return false
        // 1:1 only, and only against a real text body (never media-only).
        if (recipientsFor(thread).size != 1) return false
        if (target.body.isBlank()) return false
        // Sending writes to content://sms — only the default SMS app may do that.
        if (!context.isDefaultSmsApp()) return false

        val composed = composeReactionFallback(emoji, target.body, isRemoval) ?: return false

        // Belt-and-braces: match the quote against the thread's real message bubbles (the
        // target INCLUDED — it is what the quote must resolve to). Only reaction fallbacks are
        // excluded, mirroring sync's pool, so the quote can't latch onto another fallback.
        val candidates = uiState.value.messages.filter { !reactionParser.isReactionFallback(it.body) }
        val safe = reactionFallbackRoundTrips(
            composed = composed,
            targetMessageId = target.id,
            quoteOf = { reactionParser.parse(it)?.quotedText },
            findOriginalId = { reactionParser.findOriginalMessage(it, candidates)?.id },
        )
        if (!safe) return false

        val now = System.currentTimeMillis()
        dispatchSmsSend(thread.address, composed, -now, now, notifyScroll = false)
        return true
    }

    // ── Timestamps ────────────────────────────────────────────────────────────

    fun toggleTimestamp(messageId: Long) {
        _expandedTimestampIds.update { cur ->
            if (messageId in cur) cur - messageId else cur + messageId
        }
    }

    // ── Bubble font scale (pinch-to-zoom) ─────────────────────────────────────

    /** Live font-scale multiplier (0.8 – 1.6) for message bubble text. */
    val bubbleFontScale: StateFlow<Float> = fontScaleRepo.scale

    /**
     * Adjusts the bubble font scale by [delta]. Clamping is applied inside the repository.
     * Triggered by a pinch gesture on the thread content area.
     */
    fun adjustFontScale(delta: Float) { fontScaleRepo.adjust(delta) }

    /** Global bubble shape style (rounded / pill / square). Set from AppearanceScreen. */
    val bubbleStyle: StateFlow<BubbleStylePreference> = bubbleStyleRepo.preference

    /** Resets bubble font scale to 1.0 (e.g. from the ⋮ menu). */
    fun resetFontScale() { fontScaleRepo.reset() }

    // ── Day select (kept for backward compatibility) ──────────────────────────

    fun selectDay(dayStartMs: Long, dayEndMs: Long) {
        viewModelScope.launch {
            val dayMessages = messageRepository.getByThreadAndDateRange(threadId, dayStartMs, dayEndMs)
            _selectionState.update { cur -> cur + dayMessages.map { it.id } }
        }
    }

    // ── Date range selection ──────────────────────────────────────────────────

    fun selectByDateRange(start: LocalDate, end: LocalDate) {
        viewModelScope.launch {
            val (startMs, endMs) = epochMsForDayBoundaries(start, end)
            val messages = messageRepository.getByThreadAndDateRange(threadId, startMs, endMs)
            // Replace, don't add: picking a range means "select exactly these".
            // Adding was a silent no-op when ALL was active (everything already
            // selected, chip stuck on ALL — found on-device July 16).
            _selectionState.value = messages.mapTo(mutableSetOf()) { it.id }
            _selectionScope.value = SelectionScope.MESSAGES
        }
    }

    // ── Reply / Send ──────────────────────────────────────────────────────────

    fun onReplyTextChanged(text: String) {
        _replyText.value = text
        savedStateHandle[DRAFT_TEXT_KEY] = text
    }

    fun dismissDefaultSmsDialog() { _showDefaultSmsDialog.value = false }

    /**
     * Stores the media files the user has chosen to attach, replacing any previous
     * selection. Clears automatically after [sendMessage] picks them up, or via
     * [clearAttachments].
     *
     * Triggers: user taps the attach button and picks photos/videos (multi-select
     * photo picker) or an audio file.
     *
     * Videos longer than [MmsManagerWrapper.MAX_VIDEO_DURATION_MS] are dropped here,
     * before the user ever composes a message around them — failing at send time would
     * mean waiting through an actual transcode attempt first. This is a UX-level cap
     * independent of the byte-budget math in [MmsManagerWrapper]; retrying a previously
     * accepted attachment never re-checks it, since it already passed this gate once.
     */
    fun onAttachmentsSelected(attachments: List<MessageAttachment>) {
        if (attachments.none { it.mimeType.startsWith("video/", ignoreCase = true) }) {
            appendAttachments(attachments)
            return
        }
        viewModelScope.launch {
            val videoDurationsMs = attachments
                .filter { it.mimeType.startsWith("video/", ignoreCase = true) }
                .associate { it.uri to mmsManagerWrapper.videoDurationMs(android.net.Uri.parse(it.uri)) }

            val (accepted, rejectedCount) = partitionAttachmentsByDuration(
                attachments, videoDurationsMs, MmsManagerWrapper.MAX_VIDEO_DURATION_MS
            )
            appendAttachments(accepted)
            if (rejectedCount > 0) {
                val maxSec = MmsManagerWrapper.MAX_VIDEO_DURATION_MS / 1000
                _attachmentRejectedEvent.tryEmit(
                    if (rejectedCount == 1) "Video is longer than ${maxSec}s and wasn't attached"
                    else "$rejectedCount videos are longer than ${maxSec}s and weren't attached"
                )
            }
        }
    }

    /** Appends a picker batch to what's already queued — re-opening the picker used
     *  to REPLACE the queue, so photos couldn't be added one at a time (found
     *  on-device July 16). Deduped by uri, capped at [MAX_ATTACHMENTS] total. */
    private fun appendAttachments(newOnes: List<MessageAttachment>) {
        val current = _pendingAttachments.value
        val existingUris = current.mapTo(mutableSetOf()) { it.uri }
        val merged = current + newOnes.filter { it.uri !in existingUris }
        val capped = merged.take(MAX_ATTACHMENTS)
        if (merged.size > MAX_ATTACHMENTS) {
            _attachmentRejectedEvent.tryEmit(
                "Up to $MAX_ATTACHMENTS attachments per message — extras weren't added"
            )
        }
        _pendingAttachments.value = capped
        savedStateHandle[DRAFT_ATTACHMENTS_KEY] = encodeAttachmentsJson(capped)
    }

    /** Removes one pending attachment (user taps the × on its preview thumbnail). */
    fun removeAttachment(index: Int) {
        val removed = _pendingAttachments.value.getOrNull(index)
        // The chip is about to disappear below with no UI left to stop it — pause the
        // shared player if it's the one currently loaded (any audio attachment, not
        // just memos; a picked audio file ghosts the same way). Mirrors deletePreviewTake.
        if (removed != null && removed.uri == audioPlayer.state.value.uri) audioPlayer.pause()
        val updated = _pendingAttachments.value.filterIndexed { i, _ -> i != index }
        _pendingAttachments.value = updated
        savedStateHandle[DRAFT_ATTACHMENTS_KEY] = encodeAttachmentsJson(updated)
        // Drop this uri's display waveform (no-op for non-memo attachments).
        removed?.let { removeWaveform(it.uri) }
        // A removed voice memo's recording file is exclusively owned by this pending
        // entry (unique per recording, never referenced by a message row while pending),
        // so delete it here — no other flow ever will. No-op for non-memo attachments.
        removed?.let { att ->
            viewModelScope.launch(Dispatchers.IO) {
                MmsManagerWrapper.deleteVoiceMemoCacheFile(context, att)
            }
        }
    }

    /** Clears all pending attachments. */
    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
        savedStateHandle.remove<String>(DRAFT_ATTACHMENTS_KEY)
    }

    // ── Camera capture ─────────────────────────────────────────────────────────
    // No CAMERA permission is requested or declared anywhere in this app (see
    // AndroidManifest). ActivityResultContracts.TakePicture() hands the capture off
    // to whatever camera app is already installed — that app needs no permission
    // grant from US, since the photo lands in a file WE own and hand it via a
    // FileProvider uri. This is a deliberate zero-permission-surface choice: if
    // CAMERA is ever added to the manifest for an unrelated reason, this flow
    // breaks for anyone who denies it (the system will then require it before the
    // camera app can be launched this way).

    /** URI of a just-minted, not-yet-resolved capture target — survives process
     *  death (the camera app runs in a separate process/task while ours may be
     *  killed) so [onCameraCaptureResult] can still find the right file when the
     *  camera app returns. Null once the result is processed either way. */
    private var pendingCameraCaptureUri: String?
        get() = savedStateHandle.get<String>(DRAFT_CAMERA_CAPTURE_KEY)
        set(value) {
            if (value != null) savedStateHandle[DRAFT_CAMERA_CAPTURE_KEY] = value
            else savedStateHandle.remove<String>(DRAFT_CAMERA_CAPTURE_KEY)
        }

    /** Fires the filesDir-backed capture-target URI the UI should hand to
     *  `TakePicture().launch(...)`. Emitted only from a fresh [requestCameraCapture]
     *  call — NOT replayed on process-death restore, since the camera app is
     *  already in the foreground by then and Android redelivers its result to the
     *  recreated launcher on its own; re-launching here would reopen the camera
     *  on top of the returning one. */
    private val _cameraCaptureRequestEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val cameraCaptureRequestEvent: SharedFlow<String> = _cameraCaptureRequestEvent.asSharedFlow()

    /** Mints a fresh capture target and asks the UI to launch the system camera at
     *  it (attach-menu "Take photo"). Same cap guard as starting a voice memo
     *  recording — checked here, not duplicated in the UI. */
    fun requestCameraCapture() {
        if (_pendingAttachments.value.size >= MAX_ATTACHMENTS) {
            _attachmentRejectedEvent.tryEmit(
                "Up to $MAX_ATTACHMENTS attachments per message — remove one to take a photo"
            )
            return
        }
        val file = MmsManagerWrapper.cameraCaptureFile(context, System.currentTimeMillis())
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        } catch (e: Exception) {
            _attachmentRejectedEvent.tryEmit("Couldn't open camera")
            return
        }
        pendingCameraCaptureUri = uri
        _cameraCaptureRequestEvent.tryEmit(uri)
    }

    /** Result of the `TakePicture` launcher started by [requestCameraCapture].
     *  Success funnels the capture into the pending-attachment strip through the
     *  same path a picked photo takes; cancel/failure just deletes the unused
     *  temp file — the user backed out, not an error, so no snackbar. */
    fun onCameraCaptureResult(success: Boolean) {
        val uri = pendingCameraCaptureUri ?: return
        pendingCameraCaptureUri = null
        if (success) {
            onAttachmentsSelected(listOf(MessageAttachment(uri, "image/jpeg")))
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                MmsManagerWrapper.deleteVoiceMemoCacheFile(context, MessageAttachment(uri, "image/jpeg"))
            }
        }
    }

    /**
     * Sets the message being quoted in the reply bar (triggered by swipe-to-reply).
     * Clears automatically when the user sends a message or taps the × in the quote strip.
     */
    fun setReplyingTo(messageId: Long) { _replyingToId.value = messageId }

    /** Clears the swipe-to-reply quote strip without sending. */
    fun clearReplyingTo() { _replyingToId.value = null }

    // ── Voice memo recording ──────────────────────────────────────────────────

    /** Recording phase + start timestamp for the reply bar's mic button. [startedAtMs] is
     *  an `elapsedRealtime()` timestamp (monotonic — immune to an NTP step mid-recording),
     *  NOT wall-clock epoch millis; the UI derives the elapsed timer from it and
     *  [maxDurationMs] caps it (MMS byte budget). [previewUri] is the stopped-but-not-yet-
     *  attached take shown by the preview panel (non-null exactly in [VoiceMemoPhase.PREVIEW]). */
    data class VoiceMemoUiState(
        val phase: VoiceMemoPhase = VoiceMemoPhase.IDLE,
        val startedAtMs: Long = 0L,
        val maxDurationMs: Long = MmsManagerWrapper.MAX_VOICE_MEMO_DURATION_MS,
        val previewUri: String? = null
    )

    // Restored across process death like _pendingAttachments above: a parked PREVIEW
    // take otherwise has no survivor — onCleared (which deletes it) only runs on a
    // normal ViewModel clear, never on process death, so without this the file would
    // leak until the 24 h sweep and the panel would just vanish on restore.
    private val _voiceMemo = MutableStateFlow(
        savedStateHandle.get<String>(DRAFT_PREVIEW_MEMO_KEY)?.let { uri ->
            VoiceMemoUiState(phase = VoiceMemoPhase.PREVIEW, previewUri = uri)
        } ?: VoiceMemoUiState()
    )
    val voiceMemoState: StateFlow<VoiceMemoUiState> = _voiceMemo.asStateFlow()

    /** Every write to [_voiceMemo] goes through here so [DRAFT_PREVIEW_MEMO_KEY] stays in
     *  sync with [VoiceMemoUiState.previewUri] — the one place a parked PREVIEW take's uri
     *  is persisted so it survives process death (pending attachments already do via
     *  SavedStateHandle; a parked preview take didn't until this). */
    private fun setVoiceMemo(state: VoiceMemoUiState) {
        _voiceMemo.value = state
        if (state.previewUri != null) savedStateHandle[DRAFT_PREVIEW_MEMO_KEY] = state.previewUri
        else savedStateHandle.remove<String>(DRAFT_PREVIEW_MEMO_KEY)
    }

    init {
        // Touch every restored draft (pending attachments + a restored PREVIEW take)
        // so its 24 h orphan-sweep clock restarts — it's being actively kept, not
        // abandoned, and mtime is otherwise still whatever it was when last written.
        // No-op (and no IO hop) when nothing was restored, the overwhelmingly common case.
        val restoredPreviewUri = _voiceMemo.value.previewUri
        if (_pendingAttachments.value.isNotEmpty() || restoredPreviewUri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                _pendingAttachments.value.forEach {
                    MmsManagerWrapper.touchVoiceMemoCacheFile(context, it)
                }
                restoredPreviewUri?.let {
                    MmsManagerWrapper.touchVoiceMemoCacheFile(context, MessageAttachment(it, "audio/mp4"))
                }
            }
        }
        // The carrier-accurate cap needs a binder call, so it's only known
        // asynchronously; currentVoiceMemoCapMs() hops to IO itself. This only ever
        // shortens VoiceMemoUiState's default MAX_VOICE_MEMO_DURATION_MS seed
        // (effectiveVoiceMemoCapMs can't lengthen it), so a press that races this read
        // (recording starts before it lands) just uses the fixed default cap — the
        // memo still fits every carrier that read would have applied, just not as
        // tightly on the strictest ones.
        viewModelScope.launch {
            val capMs = mmsManagerWrapper.currentVoiceMemoCapMs()
            setVoiceMemo(_voiceMemo.value.copy(maxDurationMs = capMs))
        }
    }

    /** Live 0..1 mic input level while recording, 0f otherwise. Exposed as a flow so the
     *  level meter collects it locally — its ~15 Hz ticks then recompose only the meter,
     *  never the whole reply bar (the same isolation the audio chips use for playback). */
    private val _recordingLevel = MutableStateFlow(0f)
    val recordingLevel: StateFlow<Float> = _recordingLevel.asStateFlow()
    private var recordingLevelJob: Job? = null

    /** Amplitude samples for the current take, appended live by the level ticker below
     *  (one driver, all on Main — no synchronization needed). Cleared in [startRecorder]
     *  on success (covers START and RESTART_RECORDING); resampled into [_memoWaveforms]
     *  when a take is kept. A ~1:42 memo is ≈1,530 floats. */
    private val waveformBuilder = mutableListOf<Float>()

    /** uri → resampled display waveform for recorded memos still living in the reply bar
     *  (preview panel + pending strip). Chips look their entry up by uri; a missing entry
     *  degrades to the plain Slider. Bounded ≤6 by construction (5 pending + 1 preview);
     *  bubbles never use it. Survives process death via [DRAFT_WAVEFORMS_KEY] (a
     *  Serializable HashMap<String, FloatArray>), restored below. */
    private val _memoWaveforms = MutableStateFlow<Map<String, List<Float>>>(
        savedStateHandle.get<HashMap<String, FloatArray>>(DRAFT_WAVEFORMS_KEY)
            ?.mapValues { it.value.toList() }
            ?: emptyMap()
    )
    val memoWaveforms: StateFlow<Map<String, List<Float>>> = _memoWaveforms.asStateFlow()

    /** Stores [samples] under [uri], keeping the flow and SavedStateHandle in sync
     *  (mirrors the [setVoiceMemo] pattern). */
    private fun putWaveform(uri: String, samples: List<Float>) {
        _memoWaveforms.update { it + (uri to samples) }
        persistWaveforms()
    }

    /** Drops [uri]'s waveform, keeping the flow and SavedStateHandle in sync. */
    private fun removeWaveform(uri: String) {
        _memoWaveforms.update { it - uri }
        persistWaveforms()
    }

    private fun persistWaveforms() {
        savedStateHandle[DRAFT_WAVEFORMS_KEY] =
            HashMap(_memoWaveforms.value.mapValues { it.value.toFloatArray() })
    }

    init {
        // One driver for the meter: poll the recorder only while audio is actually being
        // captured (HELD/LOCKED), and cancel the ticker + zero the level the instant the
        // phase leaves those states. Keyed on the phase alone (distinctUntilChanged) so
        // the ticker starts and stops exactly once per take.
        viewModelScope.launch {
            _voiceMemo.map { it.phase }.distinctUntilChanged().collect { phase ->
                val capturing = phase == VoiceMemoPhase.HELD || phase == VoiceMemoPhase.LOCKED
                if (capturing && recordingLevelJob?.isActive != true) {
                    recordingLevelJob = viewModelScope.launch {
                        while (isActive) {
                            val level =
                                normalizedRecordingLevel(voiceMemoRecorder.currentMaxAmplitude())
                            _recordingLevel.value = level
                            // Same tick feeds the take's amplitude history — captured for
                            // free here, resampled into a display waveform when kept.
                            waveformBuilder.add(level)
                            delay(66) // ~15 Hz
                        }
                    }
                } else if (!capturing) {
                    recordingLevelJob?.cancel()
                    recordingLevelJob = null
                    _recordingLevel.value = 0f
                }
            }
        }
    }

    /** Starts a fresh recording; returns its start timestamp, or null (with a user-facing
     *  message) when the recorder couldn't start. */
    private fun startRecorder(maxDurationMs: Long): Long? {
        // One audio pipeline at a time: recording over playback would capture it.
        audioPlayer.pause()
        // Epoch millis names the file (must stay unique/meaningful on disk); elapsed
        // time is measured separately below via elapsedRealtime() (monotonic — see
        // VoiceMemoUiState.startedAtMs), since the two have different jobs.
        val epochMs = System.currentTimeMillis()
        val started = voiceMemoRecorder.start(
            outputFile    = MmsManagerWrapper.voiceMemoCacheFile(context, epochMs),
            maxDurationMs = maxDurationMs.toInt(),
            bitrateBps    = MmsManagerWrapper.VOICE_MEMO_BITRATE_BPS,
            onMaxDurationReached = { onVoiceMemoEvent(VoiceMemoEvent.CAP_REACHED) },
            // Media server death / mic seized mid-recording: only fires while HELD/LOCKED,
            // where CANCEL → STOP_DISCARD safely deletes the partial file (a no-op transition
            // everywhere else in the table).
            onError = {
                _attachmentRejectedEvent.tryEmit("Recording failed")
                onVoiceMemoEvent(VoiceMemoEvent.CANCEL)
            }
        )
        if (!started) {
            _attachmentRejectedEvent.tryEmit("Couldn't start recording")
            return null
        }
        // Fresh take → fresh amplitude history. Clearing here (not in the level ticker)
        // is deliberate: a hands-free RESTART keeps the phase LOCKED, so the ticker never
        // stops/restarts across it — clearing there would wipe nothing on START and drop
        // the new take's opening samples on RESTART.
        waveformBuilder.clear()
        return SystemClock.elapsedRealtime()
    }

    /** Stops the recorder keeping the take, and returns its FileProvider URI — or null
     *  (take discarded, [tooShortMessage] shown when that's the cause) when nothing
     *  usable was captured. */
    private fun stopRecorderForKeep(startedAtMs: Long, tooShortMessage: String): String? {
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        if (!isMemoKeepable(elapsedMs)) {
            voiceMemoRecorder.stopAndDiscard()
            _attachmentRejectedEvent.tryEmit(tooShortMessage)
            return null
        }
        val file = voiceMemoRecorder.stopAndKeep() ?: return null
        return try {
            FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            ).toString()
        } catch (e: Exception) {
            runCatching { file.delete() }
            _attachmentRejectedEvent.tryEmit("Couldn't save recording")
            null
        }
    }

    /** Deletes the recording file behind a preview [uri]; pauses playback first in case
     *  the user is listening to the very take being thrown away. */
    private fun deletePreviewTake(uri: String) {
        audioPlayer.pause()
        viewModelScope.launch(Dispatchers.IO) {
            MmsManagerWrapper.deleteVoiceMemoCacheFile(context, MessageAttachment(uri, "audio/mp4"))
        }
    }

    /**
     * Single entry point for every voice memo interaction (mic press, slide-latch,
     * release, stop, cancel, duration cap, and the panel's restart/attach). Applies the
     * pure [voiceMemoTransition] table and executes the returned effect — the UI never
     * talks to the recorder directly. Caller guarantees RECORD_AUDIO is granted before
     * sending [VoiceMemoEvent.PRESS].
     */
    fun onVoiceMemoEvent(event: VoiceMemoEvent) {
        val current = _voiceMemo.value
        val (phase, effect) = voiceMemoTransition(current.phase, event)
        when (effect) {
            VoiceMemoEffect.START -> {
                if (_pendingAttachments.value.size >= MAX_ATTACHMENTS) {
                    _attachmentRejectedEvent.tryEmit(
                        "Up to $MAX_ATTACHMENTS attachments per message — remove one to record"
                    )
                    return
                }
                val startedAt = startRecorder(current.maxDurationMs) ?: return
                setVoiceMemo(current.copy(phase = phase, startedAtMs = startedAt))
            }
            // Quick flow (held → release): straight into the pending queue; the
            // strip's chip (play / scrub / duration / ×) is the review step.
            VoiceMemoEffect.STOP_KEEP -> {
                setVoiceMemo(current.copy(phase = phase))
                val uri = stopRecorderForKeep(
                    current.startedAtMs, "Hold the mic button to record a voice memo"
                ) ?: return
                putWaveform(uri, resampleAmplitudes(waveformBuilder, VOICE_WAVEFORM_BUCKETS))
                appendAttachments(listOf(MessageAttachment(uri, "audio/mp4")))
            }
            // Deliberate flow (locked → stop): hold the take for the preview panel.
            VoiceMemoEffect.STOP_PREVIEW -> {
                val uri = stopRecorderForKeep(current.startedAtMs, "Recording was too short")
                setVoiceMemo(
                    if (uri != null) current.copy(phase = phase, previewUri = uri)
                    else current.copy(phase = VoiceMemoPhase.IDLE, previewUri = null)
                )
                if (uri != null) {
                    putWaveform(uri, resampleAmplitudes(waveformBuilder, VOICE_WAVEFORM_BUCKETS))
                }
            }
            VoiceMemoEffect.ATTACH_PREVIEW -> {
                val uri = current.previewUri
                setVoiceMemo(current.copy(phase = phase, previewUri = null))
                if (uri != null) appendAttachments(listOf(MessageAttachment(uri, "audio/mp4")))
            }
            VoiceMemoEffect.DISCARD_PREVIEW -> {
                setVoiceMemo(current.copy(phase = phase, previewUri = null))
                current.previewUri?.let {
                    deletePreviewTake(it)
                    removeWaveform(it)
                }
            }
            VoiceMemoEffect.RESTART_RECORDING -> {
                // Drop the current take — an in-flight locked recording or a held
                // preview — and immediately record a new one, still hands-free.
                voiceMemoRecorder.stopAndDiscard()
                current.previewUri?.let {
                    deletePreviewTake(it)
                    removeWaveform(it)
                }
                val startedAt = startRecorder(current.maxDurationMs)
                setVoiceMemo(
                    if (startedAt != null) current.copy(phase = phase, startedAtMs = startedAt, previewUri = null)
                    else current.copy(phase = VoiceMemoPhase.IDLE, previewUri = null)
                )
            }
            VoiceMemoEffect.STOP_DISCARD -> {
                setVoiceMemo(current.copy(phase = phase))
                voiceMemoRecorder.stopAndDiscard()
            }
            VoiceMemoEffect.NONE -> {
                if (phase != current.phase) setVoiceMemo(current.copy(phase = phase))
            }
        }
    }

    /** Called when the host activity stops (home, screen-off): backgrounded apps get
     *  silence from the mic, so an in-flight recording must be parked, not left running.
     *  LOCKED parks to the preview panel; HELD keeps the take via the quick flow (the
     *  finger is effectively gone). */
    fun onHostStopped() {
        when (_voiceMemo.value.phase) {
            VoiceMemoPhase.LOCKED -> onVoiceMemoEvent(VoiceMemoEvent.STOP_TAP)
            VoiceMemoPhase.HELD   -> onVoiceMemoEvent(VoiceMemoEvent.RELEASE)
            else -> Unit
        }
    }

    /** Back-press while a memo is in flight must never destroy the take: HELD keeps it
     *  via the quick flow, LOCKED parks it in the preview panel, PREVIEW attaches it to
     *  the pending strip (where it survives as a draft). Back only navigates once the
     *  memo state is IDLE again. */
    fun onBackDuringMemo() {
        when (_voiceMemo.value.phase) {
            VoiceMemoPhase.HELD    -> onVoiceMemoEvent(VoiceMemoEvent.RELEASE)
            VoiceMemoPhase.LOCKED  -> onVoiceMemoEvent(VoiceMemoEvent.STOP_TAP)
            VoiceMemoPhase.PREVIEW -> onVoiceMemoEvent(VoiceMemoEvent.ATTACH)
            VoiceMemoPhase.IDLE    -> Unit
        }
    }

    // ── Audio playback (one player per thread screen — perf-analysis #30) ─────

    /* The wrapper is cheap to construct (a StateFlow); the ExoPlayer inside is only
     * built on the first play-tap, so threads without audio never pay for one.
     * Created/driven on Main (UI callbacks + onCleared), which ExoPlayer requires. */
    private val audioPlayer = ThreadAudioPlayer(context, viewModelScope)

    /** Playback state for the audio chips; collected per-chip so position ticks
     *  only recompose chips. */
    val audioPlayback: StateFlow<AudioPlaybackState> get() = audioPlayer.state

    /** Play/pause toggle for the audio attachment at [uri] (bubble chip or pending
     *  preview tile). Loading a new URI implicitly stops whatever else was playing. */
    fun playPauseAudio(uri: String) = audioPlayer.playPause(uri)

    /** Seeks the currently loaded audio to [fraction] of its duration. */
    fun seekAudio(uri: String, fraction: Float) = audioPlayer.seekTo(uri, fraction)

    override fun onCleared() {
        // Discard any in-flight recording (deletes its temp file), delete an
        // unattached preview take, and free the player. The file delete runs inline
        // (viewModelScope is already cancelled here) — it's one small unlink.
        voiceMemoRecorder.stopAndDiscard()
        _voiceMemo.value.previewUri?.let { uri ->
            MmsManagerWrapper.deleteVoiceMemoCacheFile(context, MessageAttachment(uri, "audio/mp4"))
        }
        // Process death skips onCleared — which is exactly why the init-block restore
        // above works. A normal clear reaches here and just deleted the file above, so
        // the key must go too or a later restore would resurrect a uri to nothing.
        savedStateHandle.remove<String>(DRAFT_PREVIEW_MEMO_KEY)
        audioPlayer.release()
        super.onCleared()
    }

    /**
     * Sends the current reply text and/or pending attachment.
     *
     * Chooses the MMS or SMS path automatically. Inserts an optimistic [Message]
     * (negative ID) so the UI shows the message immediately, then dispatches it
     * to the radio. On local failure the optimistic message is marked FAILED so
     * the user sees the red-! retry indicator.
     */
    fun sendMessage() {
        val text        = _replyText.value.trim()
        val attachments = _pendingAttachments.value
        // Require at least text OR an attachment; also block re-entrant sends.
        if ((text.isEmpty() && attachments.isEmpty()) || _isSending.value) return

        if (!context.isDefaultSmsApp()) {
            _showDefaultSmsDialog.value = true
            return
        }

        val thread = uiState.value.thread ?: return
        _replyText.value = ""
        savedStateHandle.remove<String>(DRAFT_TEXT_KEY)
        clearAttachments()
        clearReplyingTo()
        // The pending chips just disappeared above; after send the bubble references a
        // different pinned uri, so a playing one would otherwise orphan. Mirrors the
        // same guard in removeAttachment / deletePreviewTake.
        if (attachments.any { it.uri == audioPlayer.state.value.uri }) audioPlayer.pause()
        // The reply-bar waveforms were only for review; the bubble chips use the Slider,
        // so drop every sent attachment's entry.
        attachments.forEach { removeWaveform(it.uri) }

        viewModelScope.launch {
            android.os.Trace.beginSection("ThreadViewModel.sendMessage")
            try {
            _isSending.value = true
            val now    = System.currentTimeMillis()
            val tempId = -now

            /* Recipients: the full roster for a group thread, else the single address.
             * A group text-only reply must go as MMS (recipients.size > 1) so it reaches
             * everyone in one group thread; 1:1 text-only stays on the cheap SMS path. */
            val recipients = sendRecipientsFor(thread)
            val useMms = attachments.isNotEmpty() || recipients.size > 1

            if (useMms) {
                // ── MMS path ──────────────────────────────────────────────────
                // Optimistic message shown immediately with attachment previews.
                val optimistic = Message(
                    id             = tempId,
                    threadId       = threadId,
                    address        = thread.address,
                    body           = text,
                    timestamp      = now,
                    isSent         = true,
                    type           = Telephony.Mms.MESSAGE_BOX_SENT,
                    deliveryStatus = DELIVERY_STATUS_PENDING,
                    isMms          = true,
                    attachments    = attachments
                )
                messageRepository.insert(optimistic)
                /* Emit the optimistic row's id so the UI can wait until that row has
                 * re-emitted, recomposed, and laid out before scrolling — insert order
                 * alone never guaranteed the row was in the composed list, which let the
                 * scroll land on the old newest message instead of the new one. */
                _scrollToBottomEvent.tryEmit(tempId)
                /* PendingIntent for MmsSentReceiver — updates Room when the MMSC
                 * responds. EXTRA_SENT_AT_MS becomes the provider row's date when the
                 * receiver persists the sent MMS (persistSentMms), keeping send-time order. */
                val reqCode   = (tempId and 0x3FFF_FFFFL).toInt()
                val sentIntent = PendingIntent.getBroadcast(
                    context, reqCode,
                    Intent(context, MmsSentReceiver::class.java).apply {
                        action = MmsSentReceiver.ACTION_MMS_SENT
                        putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, tempId)
                        putExtra(MmsSentReceiver.EXTRA_SENT_AT_MS, now)
                        // persistSentMms writes the canonical thread_id and one TO addr row
                        // per recipient from this roster.
                        putExtra(MmsSentReceiver.EXTRA_TO_ADDRESSES, recipients.toTypedArray())
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
                val dispatched = mmsManagerWrapper.sendMms(
                    toAddresses = recipients,
                    textBody    = text,
                    attachments = attachments,
                    messageId   = tempId,
                    sentIntent  = sentIntent
                )
                /* Local failure (bad URI, IO error, telephony exception): mark FAILED
                 * immediately so the message doesn't stay stuck as PENDING forever. */
                if (!dispatched) {
                    messageRepository.updateDeliveryStatus(tempId, DELIVERY_STATUS_FAILED)
                } else {
                    /* Pin the optimistic row's attachments to the stable filesDir cache
                     * files written by MmsManagerWrapper. Samsung's content://mms/part/ data
                     * for sent messages is often empty — using our own cached bytes ensures
                     * the media stays visible after SmsSyncHandler swaps the real row in. */
                    try {
                        val pinned = attachments.mapIndexed { index, att ->
                            val cacheFile = MmsManagerWrapper.attachmentCacheFile(context, tempId, index)
                            if (cacheFile.exists()) {
                                val stableUri = FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", cacheFile
                                ).toString()
                                MessageAttachment(stableUri, att.mimeType)
                            } else att
                        }
                        messageRepository.updateAttachments(tempId, pinned)
                        /* Attachments that were re-pinned above now live in the
                         * mms_attach_ cache, so a voice memo's original recording file
                         * is referenced by nothing — delete it (no-op for non-memos).
                         * Un-pinned ones (cache write failed) keep their source file
                         * as the only readable copy for retries. */
                        withContext(Dispatchers.IO) {
                            attachments.forEachIndexed { index, att ->
                                if (MmsManagerWrapper.attachmentCacheFile(context, tempId, index).exists()) {
                                    MmsManagerWrapper.deleteVoiceMemoCacheFile(context, att)
                                }
                            }
                        }
                    } catch (_: Exception) { /* keep original picker URIs on error */ }
                }
            } else {
                // ── SMS path (existing behaviour) ─────────────────────────────
                // Shared with the outbound-reaction path (dispatchSmsSend); notifyScroll=true
                // keeps the send auto-scroll that a user-composed message expects.
                dispatchSmsSend(thread.address, text, tempId, now, notifyScroll = true)
            }
            _isSending.value = false
            } finally {
                android.os.Trace.endSection()
            }
        }
    }

    /**
     * The queue-aware 1:1 SMS dispatch shared by [sendMessage]'s SMS branch and the
     * outbound-reaction path ([maybeSendReactionFallback]).
     *
     * Delegates the actual send to the shared [SmsSendDispatcher] (the same seam
     * [ScheduledSendWorker] uses when a scheduled send fires), then emits the scroll-to-bottom
     * event a user-composed send wants — [notifyScroll] is suppressed by a reaction fallback
     * (its transient bubble shouldn't yank the list). The dispatcher inserts the optimistic row
     * before returning, so emitting the scroll cue afterwards still resolves against a real row.
     */
    private suspend fun dispatchSmsSend(
        address: String,
        body: String,
        tempId: Long,
        now: Long,
        notifyScroll: Boolean,
    ) {
        smsSendDispatcher.dispatchSmsSend(threadId, address, body, tempId, now)
        if (notifyScroll) _scrollToBottomEvent.tryEmit(tempId)
    }

    // ── Scheduled send ("Schedule send" — long-press the send button) ───────────

    /**
     * Parks the current composer text as a scheduled send at [scheduledAt].
     *
     * v1 is text-only SMS: the long-press affordance is offered only when the composer holds
     * non-blank text and no pending attachments, so this only ever runs for a plain text send.
     * Validates (non-blank body, strictly-future time) through the pure [validateScheduledSend];
     * an invalid request is a no-op (the picker sheet already rejects past times, so this is the
     * belt-and-braces guard). On success the row is inserted into the separate scheduled table,
     * its [ScheduledSendWorker] is scheduled, and the composer/draft is cleared. Requires being
     * the default SMS app (the same guard [sendMessage] uses), since the parked message WILL send.
     */
    fun scheduleSend(scheduledAt: Long) {
        val text = _replyText.value.trim()
        if (validateScheduledSend(text, scheduledAt, System.currentTimeMillis()) != ScheduleValidation.VALID) return
        if (!context.isDefaultSmsApp()) {
            _showDefaultSmsDialog.value = true
            return
        }
        val thread = uiState.value.thread ?: return
        _replyText.value = ""
        savedStateHandle.remove<String>(DRAFT_TEXT_KEY)
        clearReplyingTo()
        viewModelScope.launch {
            val id = scheduledMessageRepository.insert(
                ScheduledMessage(
                    id = 0,
                    threadId = threadId,
                    address = thread.address,
                    body = text,
                    scheduledAt = scheduledAt,
                    createdAt = System.currentTimeMillis()
                )
            )
            ScheduledSendWorker.schedule(context, id, scheduledAt)
        }
    }

    /**
     * Sends a scheduled message immediately (bubble dialog → "Send now"): cancels its pending
     * worker, deletes the row, and hands the text to the normal send path right now — so it takes
     * the identical queue-aware/optimistic-insert route a live send does.
     */
    fun sendScheduledNow(id: Long) {
        if (!context.isDefaultSmsApp()) {
            _showDefaultSmsDialog.value = true
            return
        }
        viewModelScope.launch {
            val row = scheduledMessageRepository.getById(id) ?: return@launch
            ScheduledSendWorker.cancel(context, id)
            scheduledMessageRepository.deleteById(id)
            val now = System.currentTimeMillis()
            dispatchSmsSend(row.address, row.body, -now, now, notifyScroll = true)
        }
    }

    /**
     * Edit (bubble dialog → "Edit"): the simplest correct edit — cancel the schedule, delete the
     * row, and drop the text back into the composer draft. The user re-composes (and re-schedules
     * if they still want to) from the reply bar.
     */
    fun editScheduled(id: Long) {
        viewModelScope.launch {
            val row = scheduledMessageRepository.getById(id) ?: return@launch
            ScheduledSendWorker.cancel(context, id)
            scheduledMessageRepository.deleteById(id)
            _replyText.value = row.body
            savedStateHandle[DRAFT_TEXT_KEY] = row.body
        }
    }

    /** Cancel schedule (bubble dialog → confirm): cancels the pending worker and deletes the row. */
    fun cancelScheduled(id: Long) {
        viewModelScope.launch {
            ScheduledSendWorker.cancel(context, id)
            scheduledMessageRepository.deleteById(id)
        }
    }

    /**
     * Re-sends a message whose [deliveryStatus] is [DELIVERY_STATUS_FAILED].
     *
     * For MMS: rebuilds the PendingIntent (the original FLAG_ONE_SHOT was consumed)
     * and re-invokes [MmsManagerWrapper] with the same attachment URI.
     * For SMS: calls [SmsManagerWrapper.sendTextMessage] directly.
     */
    fun retrySend(messageId: Long) {
        val message = uiState.value.messages.find { it.id == messageId } ?: return
        if (message.deliveryStatus != DELIVERY_STATUS_FAILED) return
        viewModelScope.launch {
            messageRepository.updateDeliveryStatus(messageId, DELIVERY_STATUS_PENDING)
            /* Resolve recipients from the thread, not message.address — a failed group send
             * must retry to the full roster, never to a single participant. A group thread
             * (recipients.size > 1) always retries via MMS, even a text-only reply. */
            val recipients = uiState.value.thread?.let { sendRecipientsFor(it) } ?: listOf(message.address)
            val useMms = message.isMms || recipients.size > 1
            if (useMms) {
                /* MMS retry: rebuild the sentIntent (the original was FLAG_ONE_SHOT and
                 * has already been consumed) then re-invoke MmsManagerWrapper with the
                 * same attachments. */
                val reqCode = (messageId and 0x3FFF_FFFFL).toInt()
                val sentIntent = PendingIntent.getBroadcast(
                    context, reqCode,
                    Intent(context, MmsSentReceiver::class.java).apply {
                        action = MmsSentReceiver.ACTION_MMS_SENT
                        putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, messageId)
                        /* The row's ORIGINAL timestamp, not now(): persistSentMms writes it as
                         * the provider date, so the matcher in syncLatestMms still pairs the
                         * imported row with this temp row even when the retry happens outside
                         * the match window — and the bubble keeps its original position. */
                        putExtra(MmsSentReceiver.EXTRA_SENT_AT_MS, message.timestamp)
                        // persistSentMms writes the canonical thread_id and one TO addr row per recipient.
                        putExtra(MmsSentReceiver.EXTRA_TO_ADDRESSES, recipients.toTypedArray())
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
                val dispatched = mmsManagerWrapper.sendMms(
                    toAddresses = recipients,
                    textBody    = message.body,
                    attachments = message.attachments,
                    messageId   = messageId,
                    sentIntent  = sentIntent
                )
                if (!dispatched) {
                    messageRepository.updateDeliveryStatus(messageId, DELIVERY_STATUS_FAILED)
                }
            } else {
                // SMS retry: re-send as plain text (1:1 thread only).
                smsManagerWrapper.sendTextMessage(message.address, message.body, messageId)
            }
        }
    }

    // ── Backup policy ─────────────────────────────────────────────────────────

    fun updateBackupPolicy(policy: BackupPolicy) {
        viewModelScope.launch {
            threadRepository.updateBackupPolicy(threadId, policy)
        }
    }

    // ── Mute / Pin / Notifications ────────────────────────────────────────────

    fun toggleMute() {
        val current = uiState.value.thread?.isMuted ?: return
        viewModelScope.launch {
            threadRepository.updateMuted(threadId, !current)
        }
    }


    fun togglePin() {
        val current = uiState.value.thread?.isPinned ?: return
        viewModelScope.launch {
            threadRepository.updatePinned(threadId, !current)
        }
    }

    /** Reports this thread as spam (hides it into the Spam folder + silences its
     *  notifications) or restores it. Called from the thread ⋮ menu; marking spam is
     *  confirmed in the UI first, restoring ("Not spam") is immediate. */
    fun toggleSpam() {
        val current = uiState.value.thread?.isSpam ?: return
        viewModelScope.launch {
            threadRepository.updateSpam(threadId, !current)
        }
    }

    /** Permanently dismisses this thread's suspected-spam banner (persists to prefs). The
     *  banner recomputes suspicion live, so without this it would return every time. */
    fun dismissSpamSuspicion() {
        spamSuspicionRepository.dismiss(threadId)
        _spamSuspicionDismissed.value = true
    }

    /** Permanently dismisses this thread's "Add to contacts?" banner (persists to prefs).
     *  Not called on "Add to contacts" itself — the system UI can be cancelled, so that action
     *  leaves the banner up; once the contact actually exists, [senderContactName] resolving
     *  non-null hides it naturally. */
    fun dismissSaveNumberPrompt() {
        saveNumberPromptRepository.dismiss(threadId)
        _saveNumberPromptDismissed.value = true
    }

    // ── Block number ──────────────────────────────────────────────────────────

    /** One-shot user-facing result of a block attempt — the UI shows this as a Snackbar. */
    private val _blockResultEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val blockResultEvent: SharedFlow<String> = _blockResultEvent.asSharedFlow()

    /**
     * Adds this thread's address to the system [BlockedNumberContract] provider (via
     * [BlockedNumbersRepository.block]), so the platform rejects future calls and texts from
     * it before they reach any app. Only the default SMS app (or dialer/carrier app) may
     * write to the provider — when Postmark isn't default, the result message says so
     * instead of failing silently.
     */
    fun blockNumber() {
        val address = uiState.value.thread?.address?.takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val message = if (!blockedNumbersRepository.canBlock()) {
                "Postmark must be your default SMS app to block numbers"
            } else if (blockedNumbersRepository.block(address)) {
                "Blocked $address — calls and texts from this number will be rejected"
            } else {
                "Couldn't block $address"
            }
            _blockResultEvent.emit(message)
        }
    }

    /** Flips [notificationsEnabled] for this thread. Called from the thread ⋮ menu.
     *  When set to false, [SmsReceiver] will skip posting any notification for this number. */
    fun toggleNotificationsEnabled() {
        val current = uiState.value.thread?.notificationsEnabled ?: return
        viewModelScope.launch {
            threadRepository.updateNotificationsEnabled(threadId, !current)
        }
    }

    // ── Highlight (scroll-jump target) ────────────────────────────────────────

    fun highlightMessage(messageId: Long) {
        _highlightedMessageId.value = messageId
        viewModelScope.launch {
            delay(2_000)
            _highlightedMessageId.compareAndSet(messageId, null)
        }
    }

    companion object {
        private const val DRAFT_TEXT_KEY            = "draft_text"
        private const val DRAFT_ATTACHMENTS_KEY     = "draft_attachments"
        private const val DRAFT_PREVIEW_MEMO_KEY    = "draft_preview_memo"
        private const val DRAFT_WAVEFORMS_KEY       = "draft_waveforms"
        private const val DRAFT_CAMERA_CAPTURE_KEY  = "draft_camera_capture_uri"

        /** Max attachments per outgoing MMS — shared by the picker's maxItems and
         *  the accumulate-across-picker-trips cap in [appendAttachments]. */
        const val MAX_ATTACHMENTS = 5

        val DEFAULT_QUICK_EMOJIS = listOf("❤️", "👍", "😂", "😮", "🔥")

        /** One-time Snackbar shown the first time a toggle-ON stays local-only (media or
         *  group target, or a round-trip-unsafe quote). Reactions to 1:1 text messages now
         *  DO send as Android-style fallback texts — this notice sets expectations only for
         *  the cases that still can't. */
        const val REACTIONS_LOCAL_NOTICE =
            "This reaction stays on your phone — reactions to media and group messages " +
                "aren't sent as texts yet."

        /**
         * Immutable snapshot of the long-press / selection state. The ViewModel keeps these
         * five values in separate StateFlows (for the [combine] that assembles [uiState]);
         * routing every long-press-flow mutation through this snapshot keeps the transition
         * rules in one pure, testable place instead of scattered imperative assignments.
         */
        data class SelectionSnapshot(
            val pickerMessageId: Long? = null,
            val bubbleY: Float = 0f,
            val bubbleTopY: Float = 0f,
            val isSelectionMode: Boolean = false,
            val selection: Set<Long> = emptySet(),
            val scope: SelectionScope = SelectionScope.MESSAGES
        )

        /**
         * Long-press on [messageId]: while already in selection mode this just toggles the
         * message (identical to a tap) and keeps the popup closed and the rest of the running
         * selection intact; otherwise it opens the anchored emoji popup AND enters selection
         * mode with that message as the sole selection.
         */
        internal fun longPress(
            s: SelectionSnapshot,
            messageId: Long,
            bubbleTopY: Float,
            bubbleBottomY: Float
        ): SelectionSnapshot =
            if (s.isSelectionMode) {
                s.copy(
                    selection = if (messageId in s.selection) s.selection - messageId
                                else s.selection + messageId
                )
            } else {
                SelectionSnapshot(
                    pickerMessageId = messageId,
                    bubbleY         = bubbleBottomY,
                    bubbleTopY      = bubbleTopY,
                    isSelectionMode = true,
                    selection       = setOf(messageId),
                    scope           = SelectionScope.MESSAGES
                )
            }

        /** Tap-outside / back with the popup open: close the popup only, keep selection running. */
        internal fun dismissPicker(s: SelectionSnapshot): SelectionSnapshot =
            s.copy(pickerMessageId = null, bubbleY = 0f, bubbleTopY = 0f)

        /** Leave selection entirely — also clears any open popup so nothing is orphaned. */
        internal fun exitSelection(): SelectionSnapshot = SelectionSnapshot()

        /**
         * Whether the thread gesture-tips card should be visible: only while the user hasn't
         * [dismissed] it AND the thread actually has at least one message ([messageCount] > 0).
         * The message-count gate keeps the card off a brand-new empty thread, where there is
         * nothing to swipe, long-press, or pinch yet.
         *
         * Extracted here so the visibility rule can be tested without composing the UI.
         */
        internal fun shouldShowThreadTips(dismissed: Boolean, messageCount: Int): Boolean =
            !dismissed && messageCount > 0

        /**
         * Merges [topUsed] (most-used first) with [defaults], deduplicating, and caps the result
         * at [limit]. Extracted here so it can be tested without constructing the ViewModel.
         */
        internal fun buildQuickEmojiList(
            topUsed: List<String>,
            defaults: List<String> = DEFAULT_QUICK_EMOJIS,
            limit: Int = 5
        ): List<String> {
            val merged = topUsed.toMutableList()
            defaults.forEach { if (it !in merged) merged.add(it) }
            return merged.take(limit)
        }

        /**
         * Converts a [LocalDate] range into an inclusive epoch-millisecond range.
         * Start is midnight of [start] in the system timezone; end is one millisecond
         * before midnight of the day after [end].
         *
         * Extracted to the companion object so it can be tested without constructing
         * the full ViewModel.
         */
        internal fun epochMsForDayBoundaries(start: LocalDate, end: LocalDate): Pair<Long, Long> {
            val startMs = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMs   = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
            return startMs to endMs
        }

        /**
         * Splits [attachments] into (accepted, rejectedVideoCount) for [onAttachmentsSelected]'s
         * duration cap. A video whose duration is known and exceeds [maxDurationMs] is rejected;
         * everything else (non-video attachments, and videos whose duration [videoDurationsMs]
         * doesn't resolve — e.g. an unreadable/corrupt file) is let through rather than blocked
         * on an inconclusive check, matching the same "fail open, let the send-time path reject
         * it properly" caution used elsewhere in this app.
         *
         * Extracted here so the decision logic can be tested without constructing the ViewModel.
         */
        internal fun partitionAttachmentsByDuration(
            attachments: List<MessageAttachment>,
            videoDurationsMs: Map<String, Long?>,
            maxDurationMs: Long
        ): Pair<List<MessageAttachment>, Int> {
            val accepted = mutableListOf<MessageAttachment>()
            var rejected = 0
            for (attachment in attachments) {
                val duration = videoDurationsMs[attachment.uri]
                if (duration != null && duration > maxDurationMs) {
                    rejected++
                } else {
                    accepted += attachment
                }
            }
            return accepted to rejected
        }
    }
}
