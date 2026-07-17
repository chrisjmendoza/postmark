package com.plusorminustwo.postmark.ui.thread

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.Telephony
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.contacts.lookupContactName
import com.plusorminustwo.postmark.util.isDefaultSmsApp
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.preferences.BubbleFontScaleRepository
import com.plusorminustwo.postmark.data.preferences.DraftRepository
import com.plusorminustwo.postmark.data.preferences.TimestampPreferenceRepository
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.RESTORED_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.decodeAttachmentsJson
import com.plusorminustwo.postmark.domain.model.encodeAttachmentsJson
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.voicememo.VoiceMemoEffect
import com.plusorminustwo.postmark.domain.voicememo.VoiceMemoEvent
import com.plusorminustwo.postmark.domain.voicememo.VoiceMemoPhase
import com.plusorminustwo.postmark.domain.voicememo.isMemoKeepable
import com.plusorminustwo.postmark.domain.voicememo.voiceMemoTransition
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import com.plusorminustwo.postmark.service.audio.VoiceMemoRecorder
import com.plusorminustwo.postmark.service.sms.MmsManagerWrapper
import com.plusorminustwo.postmark.service.sms.MmsSentReceiver
import com.plusorminustwo.postmark.service.sms.SmsManagerWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
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
    private val smsManagerWrapper: SmsManagerWrapper,
    private val mmsManagerWrapper: MmsManagerWrapper,
    private val timestampPrefRepo: TimestampPreferenceRepository,
    private val fontScaleRepo: BubbleFontScaleRepository,
    private val draftRepository: DraftRepository,
    private val voiceMemoRecorder: VoiceMemoRecorder
) : ViewModel() {

    private val threadId: Long = checkNotNull(savedStateHandle["threadId"])

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
    private val _reactionPickerBubbleY    = MutableStateFlow(0f)
    private val _highlightedMessageId     = MutableStateFlow<Long?>(null)
    // Pending outgoing MMS attachments; empty when composing SMS. Persisted to
    // SavedStateHandle as the same JSON encoding used by the Room column.
    private val _pendingAttachments = MutableStateFlow(
        decodeAttachmentsJson(savedStateHandle.get<String>(DRAFT_ATTACHMENTS_KEY))
    )
    // ID of the message the user is replying to (swipe-to-reply); null = no active quote.
    private val _replyingToId         = MutableStateFlow<Long?>(null)

    /* Fires once per send so the UI unconditionally scrolls to the bottom
     * regardless of the current scroll position. extraBufferCapacity=1 means
     * the emit never suspends even if the collector hasn't consumed yet. */
    private val _scrollToBottomEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToBottomEvent: SharedFlow<Unit> = _scrollToBottomEvent.asSharedFlow()

    /** Fires when [onAttachmentsSelected] drops a video for exceeding
     *  [MmsManagerWrapper.MAX_VIDEO_DURATION_MS] — the UI shows this as a Snackbar. */
    private val _attachmentRejectedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val attachmentRejectedEvent: SharedFlow<String> = _attachmentRejectedEvent.asSharedFlow()

    val timestampPreference: StateFlow<TimestampPreference> = timestampPrefRepo.preference

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

    // Named holder for the action-related sub-state to stay within combine's 5-arg limit.
    private data class InnerState(
        val replyText: String,
        val isSending: Boolean,
        val showDefaultSmsDialog: Boolean,
        val expandedTimestampIds: Set<Long>,
        val selectionScope: SelectionScope,
        val reactionPickerMessageId: Long?,
        val reactionPickerBubbleY: Float,
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
            _highlightedMessageId,
            _pendingAttachments,
            _replyingToId
        ) { arr ->
            InnerState(
                replyText               = arr[0] as String,
                isSending               = arr[1] as Boolean,
                showDefaultSmsDialog    = arr[2] as Boolean,
                expandedTimestampIds    = arr[3] as Set<Long>,
                selectionScope          = arr[4] as SelectionScope,
                reactionPickerMessageId = arr[5] as Long?,
                reactionPickerBubbleY   = arr[6] as Float,
                highlightedMessageId    = arr[7] as Long?,
                pendingAttachments      = arr[8] as List<MessageAttachment>,
                replyingToId            = arr[9] as Long?
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
            reactionPickerMessageId = inner.reactionPickerMessageId,
            reactionPickerBubbleY   = inner.reactionPickerBubbleY,
            highlightedMessageId    = inner.highlightedMessageId,
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

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectionState.value  = emptySet()
        _selectionScope.value  = SelectionScope.MESSAGES
    }

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

    fun showReactionPicker(messageId: Long, bubbleY: Float) {
        _reactionPickerMessageId.value = messageId
        _reactionPickerBubbleY.value   = bubbleY
        _selectionState.value          = setOf(messageId)
    }

    fun dismissReactionPicker() {
        _reactionPickerMessageId.value = null
        _reactionPickerBubbleY.value   = 0f
        _selectionState.value          = emptySet()
    }

    /** Transitions from single-message action mode into full multi-select mode. */
    fun enterSelectionModeFromActionMode() {
        _reactionPickerMessageId.value = null
        _reactionPickerBubbleY.value   = 0f
        _isSelectionMode.value         = true
        _selectionScope.value          = SelectionScope.MESSAGES
        // _selectionState already contains the long-pressed message
    }

    fun toggleStarred(messageId: Long) {
        viewModelScope.launch {
            val message = messageRepository.getById(messageId) ?: return@launch
            messageRepository.updateStarred(messageId, !message.isStarred)
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
            val message = messageRepository.getById(messageId) ?: return@launch
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
    }

    fun toggleReaction(messageId: Long, emoji: String) {
        viewModelScope.launch {
            val message = uiState.value.messages.find { it.id == messageId } ?: return@launch
            val myReaction = message.reactions.find {
                it.senderAddress == SELF_ADDRESS && it.emoji == emoji
            }
            if (myReaction != null) {
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
            dismissReactionPicker()
        }
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
        val updated = _pendingAttachments.value.filterIndexed { i, _ -> i != index }
        _pendingAttachments.value = updated
        savedStateHandle[DRAFT_ATTACHMENTS_KEY] = encodeAttachmentsJson(updated)
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

    /**
     * Sets the message being quoted in the reply bar (triggered by swipe-to-reply).
     * Clears automatically when the user sends a message or taps the × in the quote strip.
     */
    fun setReplyingTo(messageId: Long) { _replyingToId.value = messageId }

    /** Clears the swipe-to-reply quote strip without sending. */
    fun clearReplyingTo() { _replyingToId.value = null }

    // ── Voice memo recording ──────────────────────────────────────────────────

    /** Recording phase + start timestamp for the reply bar's mic button. The UI derives
     *  the elapsed timer from [startedAtMs]; [maxDurationMs] caps it (MMS byte budget).
     *  [previewUri] is the stopped-but-not-yet-attached take shown by the preview panel
     *  (non-null exactly in [VoiceMemoPhase.PREVIEW]). */
    data class VoiceMemoUiState(
        val phase: VoiceMemoPhase = VoiceMemoPhase.IDLE,
        val startedAtMs: Long = 0L,
        val maxDurationMs: Long = MmsManagerWrapper.MAX_VOICE_MEMO_DURATION_MS,
        val previewUri: String? = null
    )

    private val _voiceMemo = MutableStateFlow(VoiceMemoUiState())
    val voiceMemoState: StateFlow<VoiceMemoUiState> = _voiceMemo.asStateFlow()

    /** Starts a fresh recording; returns its start timestamp, or null (with a user-facing
     *  message) when the recorder couldn't start. */
    private fun startRecorder(maxDurationMs: Long): Long? {
        // One audio pipeline at a time: recording over playback would capture it.
        audioPlayer.pause()
        val now = System.currentTimeMillis()
        val started = voiceMemoRecorder.start(
            outputFile    = MmsManagerWrapper.voiceMemoCacheFile(context, now),
            maxDurationMs = maxDurationMs.toInt(),
            bitrateBps    = MmsManagerWrapper.VOICE_MEMO_BITRATE_BPS,
            onMaxDurationReached = { onVoiceMemoEvent(VoiceMemoEvent.CAP_REACHED) }
        )
        if (!started) {
            _attachmentRejectedEvent.tryEmit("Couldn't start recording")
            return null
        }
        return now
    }

    /** Stops the recorder keeping the take, and returns its FileProvider URI — or null
     *  (take discarded, [tooShortMessage] shown when that's the cause) when nothing
     *  usable was captured. */
    private fun stopRecorderForKeep(startedAtMs: Long, tooShortMessage: String): String? {
        val elapsedMs = System.currentTimeMillis() - startedAtMs
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
                _voiceMemo.value = current.copy(phase = phase, startedAtMs = startedAt)
            }
            // Quick flow (held → release): straight into the pending queue; the
            // strip's chip (play / scrub / duration / ×) is the review step.
            VoiceMemoEffect.STOP_KEEP -> {
                _voiceMemo.value = current.copy(phase = phase)
                val uri = stopRecorderForKeep(
                    current.startedAtMs, "Hold the mic button to record a voice memo"
                ) ?: return
                appendAttachments(listOf(MessageAttachment(uri, "audio/mp4")))
            }
            // Deliberate flow (locked → stop): hold the take for the preview panel.
            VoiceMemoEffect.STOP_PREVIEW -> {
                val uri = stopRecorderForKeep(current.startedAtMs, "Recording was too short")
                _voiceMemo.value = if (uri != null) {
                    current.copy(phase = phase, previewUri = uri)
                } else {
                    current.copy(phase = VoiceMemoPhase.IDLE, previewUri = null)
                }
            }
            VoiceMemoEffect.ATTACH_PREVIEW -> {
                val uri = current.previewUri
                _voiceMemo.value = current.copy(phase = phase, previewUri = null)
                if (uri != null) appendAttachments(listOf(MessageAttachment(uri, "audio/mp4")))
            }
            VoiceMemoEffect.DISCARD_PREVIEW -> {
                _voiceMemo.value = current.copy(phase = phase, previewUri = null)
                current.previewUri?.let { deletePreviewTake(it) }
            }
            VoiceMemoEffect.RESTART_RECORDING -> {
                // Drop the current take — an in-flight locked recording or a held
                // preview — and immediately record a new one, still hands-free.
                voiceMemoRecorder.stopAndDiscard()
                current.previewUri?.let { deletePreviewTake(it) }
                val startedAt = startRecorder(current.maxDurationMs)
                _voiceMemo.value = if (startedAt != null) {
                    current.copy(phase = phase, startedAtMs = startedAt, previewUri = null)
                } else {
                    current.copy(phase = VoiceMemoPhase.IDLE, previewUri = null)
                }
            }
            VoiceMemoEffect.STOP_DISCARD -> {
                _voiceMemo.value = current.copy(phase = phase)
                voiceMemoRecorder.stopAndDiscard()
            }
            VoiceMemoEffect.NONE -> {
                if (phase != current.phase) _voiceMemo.value = current.copy(phase = phase)
            }
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

        viewModelScope.launch {
            android.os.Trace.beginSection("ThreadViewModel.sendMessage")
            try {
            _isSending.value = true
            val now    = System.currentTimeMillis()
            val tempId = -now

            if (attachments.isNotEmpty()) {
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
                /* Signal scroll AFTER the insert so the message is already in the list
                 * when the UI receives the event — avoids a frame where the scroll lands
                 * on the old last message instead of the new one. */
                _scrollToBottomEvent.tryEmit(Unit)
                /* PendingIntent for MmsSentReceiver — updates Room when the MMSC
                 * responds. EXTRA_SENT_AT_MS lets the receiver find the real content-
                 * provider row even if sync replaced the temp row before MMSC replied. */
                val reqCode   = (tempId and 0x3FFF_FFFFL).toInt()
                /* Snapshot the max MMS _id before sending so MmsSentReceiver can find
                 * the real content://mms row without relying on the date field format
                 * (seconds vs milliseconds varies by device/OEM). */
                val beforeSendMaxId = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.query(
                            android.net.Uri.parse("content://mms"),
                            arrayOf("_id"), null, null, "_id DESC LIMIT 1"
                        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
                    } catch (_: Exception) { 0L }
                }
                val sentIntent = PendingIntent.getBroadcast(
                    context, reqCode,
                    Intent(context, MmsSentReceiver::class.java).apply {
                        action = MmsSentReceiver.ACTION_MMS_SENT
                        putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, tempId)
                        putExtra(MmsSentReceiver.EXTRA_SENT_AT_MS, now)
                        putExtra(MmsSentReceiver.EXTRA_BEFORE_SEND_MAX_ID, beforeSendMaxId)
                        // Lets the receiver verify/repair the platform-assigned thread_id.
                        putExtra(MmsSentReceiver.EXTRA_TO_ADDRESS, thread.address)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
                val dispatched = mmsManagerWrapper.sendMms(
                    toAddress   = thread.address,
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
                val optimistic = Message(
                    id             = tempId,
                    threadId       = threadId,
                    address        = thread.address,
                    body           = text,
                    timestamp      = now,
                    isSent         = true,
                    type           = Telephony.Sms.MESSAGE_TYPE_SENT,
                    deliveryStatus = DELIVERY_STATUS_PENDING
                )
                messageRepository.insert(optimistic)
                /* Signal scroll AFTER the insert — same rationale as the MMS path above. */
                _scrollToBottomEvent.tryEmit(Unit)
                smsManagerWrapper.sendTextMessage(thread.address, text, tempId)
            }
            _isSending.value = false
            } finally {
                android.os.Trace.endSection()
            }
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
            if (message.isMms && message.attachments.isNotEmpty()) {
                /* MMS retry: rebuild the sentIntent (the original was FLAG_ONE_SHOT and
                 * has already been consumed) then re-invoke MmsManagerWrapper with the
                 * same attachments. */
                val reqCode = (messageId and 0x3FFF_FFFFL).toInt()
                val beforeSendMaxId = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.query(
                            android.net.Uri.parse("content://mms"),
                            arrayOf("_id"), null, null, "_id DESC LIMIT 1"
                        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
                    } catch (_: Exception) { 0L }
                }
                val sentIntent = PendingIntent.getBroadcast(
                    context, reqCode,
                    Intent(context, MmsSentReceiver::class.java).apply {
                        action = MmsSentReceiver.ACTION_MMS_SENT
                        putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, messageId)
                        putExtra(MmsSentReceiver.EXTRA_SENT_AT_MS, System.currentTimeMillis())
                        putExtra(MmsSentReceiver.EXTRA_BEFORE_SEND_MAX_ID, beforeSendMaxId)
                        // Lets the receiver verify/repair the platform-assigned thread_id.
                        putExtra(MmsSentReceiver.EXTRA_TO_ADDRESS, message.address)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
                val dispatched = mmsManagerWrapper.sendMms(
                    toAddress   = message.address,
                    textBody    = message.body,
                    attachments = message.attachments,
                    messageId   = messageId,
                    sentIntent  = sentIntent
                )
                if (!dispatched) {
                    messageRepository.updateDeliveryStatus(messageId, DELIVERY_STATUS_FAILED)
                }
            } else {
                // SMS retry: re-send as plain text.
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

    // ── Block number ──────────────────────────────────────────────────────────

    /** One-shot user-facing result of a block attempt — the UI shows this as a Snackbar. */
    private val _blockResultEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val blockResultEvent: SharedFlow<String> = _blockResultEvent.asSharedFlow()

    /**
     * Adds this thread's address to the system [BlockedNumberContract] provider, so the
     * platform rejects future calls and texts from it before they reach any app.
     * Only the default SMS app (or dialer/carrier app) may write to the provider —
     * when Postmark isn't default, the result message says so instead of failing silently.
     */
    fun blockNumber() {
        val address = uiState.value.thread?.address?.takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                if (BlockedNumberContract.canCurrentUserBlockNumbers(context)) {
                    val values = ContentValues().apply {
                        put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, address)
                    }
                    context.contentResolver.insert(
                        BlockedNumberContract.BlockedNumbers.CONTENT_URI, values
                    )
                    "Blocked $address — calls and texts from this number will be rejected"
                } else {
                    "Postmark must be your default SMS app to block numbers"
                }
            } catch (e: Exception) {
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
        private const val DRAFT_TEXT_KEY        = "draft_text"
        private const val DRAFT_ATTACHMENTS_KEY = "draft_attachments"

        /** Max attachments per outgoing MMS — shared by the picker's maxItems and
         *  the accumulate-across-picker-trips cap in [appendAttachments]. */
        const val MAX_ATTACHMENTS = 5

        val DEFAULT_QUICK_EMOJIS = listOf("❤️", "👍", "😂", "😮", "🔥")

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
