package com.plusorminustwo.postmark.ui.thread

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.app.role.RoleManager
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.ui.components.ContactAvatar
import com.plusorminustwo.postmark.ui.components.DateRangeBottomSheet
import com.plusorminustwo.postmark.domain.formatter.ExportFormatter
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.Devices
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.voicememo.VoiceMemoEvent
import com.plusorminustwo.postmark.domain.voicememo.VoiceMemoPhase
import com.plusorminustwo.postmark.domain.voicememo.formatMemoDuration
import com.plusorminustwo.postmark.domain.voicememo.shouldCancelDrag
import com.plusorminustwo.postmark.domain.voicememo.shouldLatchLock
import com.plusorminustwo.postmark.ui.theme.PostmarkTheme
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as lazyRowItemsIndexed
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.progressSemantics
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * CompositionLocal carrying the current bubble font-scale multiplier (0.8 – 1.6).
 * Set by [ThreadContent] and consumed by [MessageBubble] for body text sizing.
 * Defaults to 1.0 so previews and other consumers outside the thread view are unaffected.
 */
internal val LocalBubbleFontScale = compositionLocalOf { 1.0f }

/**
 * Entry-point composable for a single conversation thread.
 *
 * Thin shell: collects state from [ThreadViewModel] and forwards it to [ThreadContent].
 * Navigation supplies [threadId] via SavedStateHandle. Optional [scrollToMessageId] and
 * [scrollToDate] params are used when arriving from search results or the calendar picker.
 *
 * @param threadId          Room primary key of the thread to display.
 * @param scrollToMessageId If >= 0, the list will scroll to and highlight this message on load.
 * @param scrollToDate      ISO-8601 date string ("yyyy-MM-dd"); if non-empty, scrolls to that day.
 * @param onBack            Called when the user presses the back/up button.
 * @param onViewStats       Navigates to the Stats screen scoped to this thread.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(
    threadId: Long,
    scrollToMessageId: Long = -1L,
    scrollToDate: String = "",
    onBack: () -> Unit,
    onViewContact: () -> Unit = {},
    onViewStats: () -> Unit = {},
    onSearchInThread: (Long) -> Unit = {},
    // Navigates to the forward destination picker for this message. Owned by the nav
    // layer (like onViewContact) rather than the ViewModel, since picking a destination
    // and sending there is itself a full screen, not in-ViewModel state.
    onForwardMessage: (Long) -> Unit = {},
    viewModel: ThreadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val timestampPref by viewModel.timestampPreference.collectAsState()
    val activeDates by viewModel.activeDates.collectAsState()
    val quickReactionEmojis by viewModel.quickReactionEmojis.collectAsState()
    // Bubble font scale — driven by pinch gesture, persisted across sessions.
    val bubbleFontScale by viewModel.bubbleFontScale.collectAsState()
    // address → name for group threads; empty for 1:1 (doubles as the group signal).
    val participantNames by viewModel.participantNames.collectAsState()
    // Voice memo recording phase for the reply bar's mic button.
    val voiceMemo by viewModel.voiceMemoState.collectAsState()

    // ── Stable lambdas ────────────────────────────────────────────────────────
    // Wrapped in remember(viewModel) so the same function reference is reused
    // across recompositions. Prevents child composables that accept lambdas from
    // recomposing just because ThreadScreen recomposed.

    // Haptics ride inside these existing callbacks — never as new gesture detectors
    // on bubbles (child detectors silently break the parent combinedClickable's
    // selection/long-press; see the July 12 note). CONTEXT_CLICK goes through the
    // View because this Compose version's HapticFeedbackType only offers
    // LongPress/TextHandleMove.
    val view    = LocalView.current
    val haptics = LocalHapticFeedback.current

    // Android 9+ feeds silence to a backgrounded app's mic, so the screen must stay on
    // for exactly as long as the mic is actually capturing — HELD or LOCKED, never
    // PREVIEW (nothing is recording then). Keyed on the boolean so a phase flip re-runs
    // this effect; onDispose always clears the flag, whether from the key changing or
    // this composable leaving — it can never be left stuck on.
    val isActivelyRecording = voiceMemo.phase == VoiceMemoPhase.HELD || voiceMemo.phase == VoiceMemoPhase.LOCKED
    DisposableEffect(isActivelyRecording) {
        view.keepScreenOn = isActivelyRecording
        onDispose { view.keepScreenOn = false }
    }

    // Screen-off/home also stops the activity, which counts as backgrounded for the
    // same mic-silence rule above — park whatever take is in flight rather than let it
    // keep "recording" silence until the user returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onHostStopped()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back during an in-flight memo must park it (onBackDuringMemo), never let
    // navigation silently discard the take — see the CHANGELOG entry on this. Compose's
    // OnBackPressedDispatcher gives priority to the most-recently-composed *enabled*
    // callback, so this is placed as the LAST BackHandler this function composes (there
    // is no other one in ThreadScreen.kt today — multi-select exits via the top bar's ×
    // instead of back — but a future one, e.g. a selection-mode close-on-back, would
    // need to be composed BEFORE this point to keep this one taking precedence while a
    // memo is active).
    BackHandler(enabled = voiceMemo.phase != VoiceMemoPhase.IDLE) {
        viewModel.onBackDuringMemo()
    }

    val onHighlightMessage        = remember(viewModel) { { id: Long -> viewModel.highlightMessage(id) } }
    val onDeleteMessage           = remember(viewModel) { { id: Long -> viewModel.deleteMessage(id) } }
    val onToggleStarred           = remember(viewModel) { { id: Long -> viewModel.toggleStarred(id) } }
    val onDismissDefaultSmsDialog = remember(viewModel) { { viewModel.dismissDefaultSmsDialog() } }
    val onUpdateBackupPolicy      = remember(viewModel) { { policy: BackupPolicy -> viewModel.updateBackupPolicy(policy) } }
    val onDismissReactionPicker   = remember(viewModel) { { viewModel.dismissReactionPicker() } }
    val onEnterSelectionModeFromActionMode = remember(viewModel) { { viewModel.enterSelectionModeFromActionMode() } }
    val onForwardMessage_         = remember(viewModel, onForwardMessage) {
        { id: Long -> viewModel.dismissReactionPicker(); onForwardMessage(id) }
    }
    val onExitSelectionMode       = remember(viewModel) { { viewModel.exitSelectionMode() } }
    val onSetSelectionScope       = remember(viewModel) { { scope: SelectionScope -> viewModel.setSelectionScope(scope) } }
    val onToggleMute              = remember(viewModel) { { viewModel.toggleMute() } }
    val onBlockNumber             = remember(viewModel) { { viewModel.blockNumber() } }
    val onTogglePin               = remember(viewModel) { { viewModel.togglePin() } }
    val onToggleNotifications     = remember(viewModel) { { viewModel.toggleNotificationsEnabled() } }
    val onEnterSelectionMode      = remember(viewModel) { { viewModel.enterSelectionMode() } }
    val onReplyTextChanged        = remember(viewModel) { { text: String -> viewModel.onReplyTextChanged(text) } }
    val onSendMessage             = remember(viewModel) { { viewModel.sendMessage() } }
    val onToggleSelection         = remember(viewModel) { { id: Long -> viewModel.toggleSelection(id) } }
    val onShowReactionPicker      = remember(viewModel, haptics) {
        { id: Long, y: Float ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.showReactionPicker(id, y)
        }
    }
    val onToggleReaction          = remember(viewModel, view) {
        { id: Long, emoji: String ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            viewModel.toggleReaction(id, emoji)
        }
    }
    val onToggleTimestamp         = remember(viewModel) { { id: Long -> viewModel.toggleTimestamp(id) } }
    val onToggleMessageIds        = remember(viewModel) { { ids: List<Long> -> viewModel.toggleMessageIds(ids) } }
    val onRetry                   = remember(viewModel) { { id: Long -> viewModel.retrySend(id) } }
    val onSelectByDateRange       = remember(viewModel) { { start: LocalDate, end: LocalDate -> viewModel.selectByDateRange(start, end) } }
    val onAttachmentsSelected     = remember(viewModel) { { attachments: List<MessageAttachment> -> viewModel.onAttachmentsSelected(attachments) } }
    val onRemoveAttachment        = remember(viewModel) { { index: Int -> viewModel.removeAttachment(index) } }
    val onSetReplyingTo           = remember(viewModel) { { id: Long -> viewModel.setReplyingTo(id) } }
    val onClearReplyingTo         = remember(viewModel) { { viewModel.clearReplyingTo() } }
    val onSearchInThread_         = remember(viewModel, threadId, onSearchInThread) { { onSearchInThread(threadId) } }
    val onAdjustFontScale         = remember(viewModel) { { delta: Float -> viewModel.adjustFontScale(delta) } }
    val onResetFontScale          = remember(viewModel) { { viewModel.resetFontScale() } }
    val onVoiceMemoEvent          = remember(viewModel) { { event: VoiceMemoEvent -> viewModel.onVoiceMemoEvent(event) } }
    val onAudioPlayPause          = remember(viewModel) { { uri: String -> viewModel.playPauseAudio(uri) } }
    val onAudioSeek               = remember(viewModel) { { uri: String, fraction: Float -> viewModel.seekAudio(uri, fraction) } }

    ThreadContent(
        uiState = uiState,
        timestampPref = timestampPref,
        activeDates = activeDates,
        quickReactionEmojis = quickReactionEmojis,
        bubbleFontScale = bubbleFontScale,
        participantNames = participantNames,
        scrollToMessageId = scrollToMessageId,
        scrollToDate = scrollToDate,
        scrollToBottomEvent = viewModel.scrollToBottomEvent,
        attachmentRejectedEvent = viewModel.attachmentRejectedEvent,
        blockResultEvent = viewModel.blockResultEvent,
        onBack = onBack,
        onViewContact = onViewContact,
        onViewStats = onViewStats,
        onHighlightMessage = onHighlightMessage,
        onDeleteMessage = onDeleteMessage,
        onToggleStarred = onToggleStarred,
        onDismissDefaultSmsDialog = onDismissDefaultSmsDialog,
        onUpdateBackupPolicy = onUpdateBackupPolicy,
        onDismissReactionPicker = onDismissReactionPicker,
        onEnterSelectionModeFromActionMode = onEnterSelectionModeFromActionMode,
        onForwardMessage = onForwardMessage_,
        onExitSelectionMode = onExitSelectionMode,
        onSetSelectionScope = onSetSelectionScope,
        onToggleMute = onToggleMute,
        onBlockNumber = onBlockNumber,
        onTogglePin = onTogglePin,
        onToggleNotifications = onToggleNotifications,
        onEnterSelectionMode = onEnterSelectionMode,
        onReplyTextChanged = onReplyTextChanged,
        onSendMessage = onSendMessage,
        onToggleSelection = onToggleSelection,
        onShowReactionPicker = onShowReactionPicker,
        onToggleReaction = onToggleReaction,
        onToggleTimestamp = onToggleTimestamp,
        onToggleMessageIds = onToggleMessageIds,
        onRetry = onRetry,
        onSelectByDateRange = onSelectByDateRange,
        onAttachmentsSelected = onAttachmentsSelected,
        onRemoveAttachment = onRemoveAttachment,
        onSetReplyingTo = onSetReplyingTo,
        onClearReplyingTo = onClearReplyingTo,
        onSearchInThread = onSearchInThread_,
        onAdjustFontScale = onAdjustFontScale,
        onResetFontScale = onResetFontScale,
        voiceMemo = voiceMemo,
        onVoiceMemoEvent = onVoiceMemoEvent,
        recordingLevel = viewModel.recordingLevel,
        audioPlayback = viewModel.audioPlayback,
        memoWaveforms = viewModel.memoWaveforms,
        onAudioPlayPause = onAudioPlayPause,
        onAudioSeek = onAudioSeek
    )
}

/**
 * Which top-bar variant is showing. [AnimatedContent] in [ThreadContent] keys on this
 * stable discriminator — keying on uiState itself would restart the transition on
 * every keystroke or selection tap, since a new state object arrives per emission.
 */
private enum class TopBarMode { ACTION, SELECTION, NORMAL }

/**
 * Non-snapshot holder retaining the last meaningful value so an [AnimatedVisibility]
 * strip still has content while it shrink-animates out. Deliberately NOT a
 * MutableState: writing snapshot state during composition schedules an extra
 * recomposition of the scope on every change (the bug class fixed July 15 at the
 * date pill). A plain field is safe here because exit-frame content is, by
 * definition, the previous composition's value — live updates must come from a
 * read of the real (observable) source inside the content lambda.
 */
private class Retained<T>(var value: T)

/**
 * Stateless composable that renders the full thread UI.
 *
 * Separated from [ThreadScreen] so it can be previewed and tested in isolation — all
 * state is passed in and all events are forwarded out via lambdas.
 *
 * Layout overview:
 *  - Scaffold with a context-sensitive top bar (normal / selection mode / action mode)
 *  - [LazyColumn] with `reverseLayout = true` — newest messages at the bottom
 *  - Floating date pill overlay at the top of the list
 *  - Scroll-to-latest FAB when scrolled up
 *  - Full-screen [EmojiReactionPopup] overlay when a bubble is long-pressed
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ThreadContent(
    uiState: ThreadUiState,
    timestampPref: TimestampPreference,
    activeDates: Set<LocalDate>,
    quickReactionEmojis: List<String>,
    bubbleFontScale: Float = 1.0f,
    // address → contact name for group threads; empty for 1:1 threads.
    participantNames: Map<String, String> = emptyMap(),
    scrollToMessageId: Long = -1L,
    scrollToDate: String = "",
    scrollToBottomEvent: SharedFlow<Unit> = MutableSharedFlow(),
    attachmentRejectedEvent: SharedFlow<String> = MutableSharedFlow(),
    blockResultEvent: SharedFlow<String> = MutableSharedFlow(),
    onBack: () -> Unit,
    onViewContact: () -> Unit = {},
    onViewStats: () -> Unit,
    onHighlightMessage: (Long) -> Unit,
    onDeleteMessage: (Long) -> Unit = {},
    onToggleStarred: (Long) -> Unit = {},
    onDismissDefaultSmsDialog: () -> Unit,
    onUpdateBackupPolicy: (BackupPolicy) -> Unit,
    onDismissReactionPicker: () -> Unit,
    onEnterSelectionModeFromActionMode: () -> Unit,
    onForwardMessage: (Long) -> Unit,
    onExitSelectionMode: () -> Unit,
    onSetSelectionScope: (SelectionScope) -> Unit,
    onToggleMute: () -> Unit,
    onBlockNumber: () -> Unit = {},
    onTogglePin: () -> Unit,
    onToggleNotifications: () -> Unit,
    onEnterSelectionMode: () -> Unit,
    onReplyTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onShowReactionPicker: (Long, Float) -> Unit,
    onToggleReaction: (Long, String) -> Unit,
    onToggleTimestamp: (Long) -> Unit,
    onToggleMessageIds: (List<Long>) -> Unit,
    onRetry: (Long) -> Unit = {},
    onSelectByDateRange: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    onAttachmentsSelected: (List<MessageAttachment>) -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onSetReplyingTo: (Long) -> Unit = {},
    onClearReplyingTo: () -> Unit = {},
    onSearchInThread: () -> Unit = {},
    onAdjustFontScale: (Float) -> Unit = {},
    onResetFontScale: () -> Unit = {},
    // Voice memo recording phase driving the reply bar's mic button / recording row.
    voiceMemo: ThreadViewModel.VoiceMemoUiState = ThreadViewModel.VoiceMemoUiState(),
    onVoiceMemoEvent: (VoiceMemoEvent) -> Unit = {},
    // Live 0..1 mic input level while recording; collected locally by the level meter.
    recordingLevel: StateFlow<Float> = MutableStateFlow(0f),
    // Shared thread-wide audio player state (perf-analysis #30). A StateFlow (stable)
    // rather than a value so position ticks recompose only the chips that collect it.
    audioPlayback: StateFlow<AudioPlaybackState> = MutableStateFlow(AudioPlaybackState()),
    // uri → display waveform for recorded memos in the reply bar (preview + pending).
    // Flows exactly like audioPlayback; the ReplyBar collects it once and resolves it.
    memoWaveforms: StateFlow<Map<String, List<Float>>> = MutableStateFlow(emptyMap()),
    onAudioPlayPause: (String) -> Unit = {},
    onAudioSeek: (String, Float) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(attachmentRejectedEvent) {
        attachmentRejectedEvent.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }

    LaunchedEffect(blockResultEvent) {
        blockResultEvent.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }

    // RoleManager.createRequestRoleIntent MUST be launched via startActivityForResult;
    // a plain startActivity() is silently ignored on API 29+.
    val roleRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    var showCalendarPicker by remember { mutableStateOf(false) }
    var showBackupPolicyDialog by remember { mutableStateOf(false) }
    var showDateRangePicker by remember { mutableStateOf(false) }
    // Blocking is a system-wide, hard-to-discover-how-to-undo action, so it confirms first.
    var showBlockConfirmDialog by remember { mutableStateOf(false) }

    // Non-null shows a "Delete message?" confirm dialog for this message id. Shared by the
    // action-bar Delete button and the image viewer's trash icon — deletion is real (removes
    // the system content://sms/mms row too, see ThreadViewModel.deleteMessage), so both
    // entry points confirm through the same dialog rather than deleting on tap.
    var pendingDeleteMessageId by remember { mutableStateOf<Long?>(null) }

    // Same nickname-falls-back-to-formatted-number resolution as the top app bar title —
    // hoisted here too since the image viewer's header needs it for the "You"/contact label.
    val contactDisplayName = uiState.thread?.let { t -> t.nickname ?: formatPhoneNumber(t.displayName) } ?: ""

    // Real navigation-bar height, read from THIS (Activity) window — not the image
    // viewer/video player Dialogs' own windows, whose WindowInsets reporting proved
    // unreliable on-device (navigationBarsPadding() inside those dialogs kept computing
    // zero on a real Samsung phone even after forcing decorFitsSystemWindows = false).
    // Passed down and applied as an explicit padding value instead of relying on
    // *.navigationBarsPadding() inside the dialogs themselves.
    val navBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Index into uiState.threadImages the full-screen viewer opens at; null = closed.
    // Lifted up here (rather than per-MessageBubble) so swiping pages across every image
    // in the thread, not just the tapped message's own attachments.
    var globalImageViewerIndex by remember { mutableStateOf<Int?>(null) }
    val onImageTap = remember(uiState.threadImages) {
        { uri: String ->
            val index = uiState.threadImages.indexOfFirst { it.uri == uri }
            if (index >= 0) globalImageViewerIndex = index
        }
    }

    // URI of the video being played; null = player closed. Lifted here (rather than
    // per-MessageBubble) so the player dialog isn't torn down when the LazyColumn item
    // hosting it is disposed during a rotation relayout.
    var playingVideoUri by remember { mutableStateOf<String?>(null) }
    val onVideoTap = remember { { uri: String -> playingVideoUri = uri } }

    // Live Y of the bubble currently selected for reaction.
    // Initialised from the ViewModel's snapshot Y (captured at long-press time),
    // then updated every layout pass by the selected bubble's onGloballyPositioned.
    // This means the popup always uses the bubble's *current* screen position, so
    // layout changes that happen right after the long-press (top-bar swap, IME
    // dismiss, etc.) are automatically corrected within a single frame.
    var liveBubbleY by remember(uiState.reactionPickerMessageId) {
        mutableFloatStateOf(uiState.reactionPickerBubbleY)
    }

    // ── Scroll to message (search-jump / image-viewer "go to chat") ───────────
    // Uses renderState.messageIdToIndex directly — no re-grouping needed.

    // Scrolls to and highlights [targetId], centering it in the viewport. Shared by the
    // search-jump navigation effect below and the image viewer's "Go to chat" action.
    // rememberUpdatedState matters: uiState is a plain parameter, so a snapshotFlow over
    // it directly would capture one value and never re-emit — if the target wasn't in the
    // map yet (thread still loading), `.first { }` suspended forever.
    val currentRenderState = rememberUpdatedState(uiState.renderState)
    suspend fun scrollToMessageCentered(targetId: Long) {
        // Wait until the target message is present in the render state.
        val targetIndex = snapshotFlow { currentRenderState.value.messageIdToIndex[targetId] }
            .first { it != null } ?: return
        // Snap to item first so layoutInfo is populated for the target.
        listState.scrollToItem(targetIndex)
        // Wait for the frame that includes the target item in visibleItemsInfo.
        snapshotFlow { listState.layoutInfo }
            .first { info -> info.visibleItemsInfo.any { it.index == targetIndex } }
        // Compute the offset that centers the item in the viewport.
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        if (item != null) {
            val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
            val centeredOffset = (viewportHeight / 2) - (item.size / 2)
            listState.animateScrollToItem(targetIndex, scrollOffset = -centeredOffset)
        }
        onHighlightMessage(targetId)
    }

    LaunchedEffect(scrollToMessageId) {
        if (scrollToMessageId == -1L) return@LaunchedEffect
        scrollToMessageCentered(scrollToMessageId)
    }

    // ── New-message auto-scroll / FAB nudge ────────────────────────────────
    // fabVisible is hoisted here so both this effect and the scroll-triggered
    // effect inside the inner Box can drive the same FAB state.
    var fabVisible by remember { mutableStateOf(false) }

    // Scroll effects — each isolated in its own helper composable so unrelated
    // state changes don't trigger other effects unnecessarily.
    ThreadScrollToBottomEffect(scrollToBottomEvent, listState)
    ThreadNewMessageScrollEffect(
        messageCount = uiState.messages.size,
        listState    = listState,
        onFabVisible = { fabVisible = it }
    )

    // ── Derived display state ─────────────────────────────────────────────────
    // All grouping, clustering, and index maps are pre-computed in ThreadViewModel
    // via buildRenderState() and live in uiState.renderState. Nothing to derive here.

    val initialScrollDateLabel = remember(scrollToDate) {
        if (scrollToDate.isEmpty()) "" else localDateToLabel(LocalDate.parse(scrollToDate))
    }

    // Keyed on the id→date map: uiState is a plain parameter, so an un-keyed remember
    // froze the lambda created on FIRST composition — when renderState was still the
    // empty initial ThreadUiState() — and every bubble-keyed lookup silently resolved
    // "" forever (masked by the header_ path and the keep-last-label logic below).
    val messageIdToDate = uiState.renderState.messageIdToDate
    val visibleDate by remember(messageIdToDate) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf ""
            val topItem = visible.maxByOrNull { it.index } ?: return@derivedStateOf ""
            when (val key = topItem.key) {
                is String -> if (key.startsWith("header_")) key.removePrefix("header_")
                             else key.toLongOrNull()?.let { messageIdToDate[it] } ?: ""
                else      -> ""
            }
        }
    }

    // Newest day's label (index 0 = newest message). Seeds the pill so opening a thread shows
    // the current day's date immediately, instead of an empty oval, in the window before the
    // list has laid out and `visibleDate` can resolve from the top visible item.
    val newestDayLabel = when (val first = uiState.renderState.items.firstOrNull()) {
        is ThreadListItem.Bubble     -> uiState.renderState.messageIdToDate[first.message.id] ?: ""
        is ThreadListItem.DateHeader -> first.dateLabel
        null                         -> ""
    }

    // Reconciled in an effect, not inline: writing snapshot state that this same
    // composition also reads scheduled a guaranteed extra recomposition pass on
    // every day-boundary crossing while scrolling (a backwards write).
    var pillDateLabel by remember { mutableStateOf("") }
    LaunchedEffect(visibleDate, newestDayLabel) {
        when {
            visibleDate.isNotEmpty() -> pillDateLabel = visibleDate
            pillDateLabel.isEmpty()  -> pillDateLabel = newestDayLabel
        }
    }

    fun scrollToDateLabel(label: String) {
        uiState.renderState.dateToHeaderIndex[label]?.let { headerIdx ->
            scope.launch {
                // Instant snap so the item is in view; scrollToItem is a suspend function
                // that waits for layout to settle, so layoutInfo is accurate immediately after.
                listState.scrollToItem(headerIdx)
                val layout = listState.layoutInfo
                val viewport = layout.viewportEndOffset - layout.viewportStartOffset
                val header = layout.visibleItemsInfo.firstOrNull { it.index == headerIdx }
                    ?: return@launch
                // In reverseLayout, scrollOffset shifts the item upward from the bottom edge.
                // (viewport - headerSize) places the header's top edge at the viewport top.
                listState.animateScrollToItem(
                    index = headerIdx,
                    scrollOffset = (viewport - header.size).coerceAtLeast(0)
                )
            }
        }
    }

    // ── One-shot scroll to target message or date ─────────────────────────────

    var initialScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.messages) {
        if (initialScrollDone || uiState.messages.isEmpty()) return@LaunchedEffect
        if (scrollToMessageId == -1L && scrollToDate.isEmpty()) { initialScrollDone = true; return@LaunchedEffect }
        val targetIdx = when {
            scrollToMessageId != -1L            -> uiState.renderState.messageIdToIndex[scrollToMessageId]
            initialScrollDateLabel.isNotEmpty() -> uiState.renderState.dateToHeaderIndex[initialScrollDateLabel]
            else                                -> null
        }
        if (targetIdx != null) {
            listState.scrollToItem(targetIdx)
            initialScrollDone = true
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showCalendarPicker) {
        CalendarPickerDialog(
            activeDates = activeDates,
            onDateSelected = { scrollTo, wasSnapped, tappedLabel ->
                showCalendarPicker = false
                scrollToDateLabel(localDateToLabel(scrollTo))
                if (wasSnapped) scope.launch {
                    snackbarHostState.showSnackbar(
                        "No messages on $tappedLabel — jumped to nearest day",
                        duration = SnackbarDuration.Short
                    )
                }
            },
            onDismiss = { showCalendarPicker = false }
        )
    }

    if (uiState.showDefaultSmsDialog) {
        AlertDialog(
            onDismissRequest = { onDismissDefaultSmsDialog() },
            title = { Text("Set Postmark as default SMS app") },
            text  = { Text("To send messages, Postmark needs to be your default SMS app.") },
            confirmButton = {
                TextButton(onClick = {
                    onDismissDefaultSmsDialog()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val rm = context.getSystemService(RoleManager::class.java)
                        roleRequestLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                    } else {
                        @Suppress("DEPRECATION")
                        roleRequestLauncher.launch(
                            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(
                                Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName
                            )
                        )
                    }
                }) { Text("Set as default") }
            },
            dismissButton = {
                TextButton(onClick = { onDismissDefaultSmsDialog() }) { Text("Not now") }
            }
        )
    }

    if (showBlockConfirmDialog) {
        val blockAddress = uiState.thread?.address ?: ""
        AlertDialog(
            onDismissRequest = { showBlockConfirmDialog = false },
            title = { Text("Block $blockAddress?") },
            text = {
                Text(
                    "Calls and texts from this number will be rejected by your phone. " +
                        "You can unblock it later from your phone's blocked-numbers settings."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBlockConfirmDialog = false
                    onBlockNumber()
                }) { Text("Block") }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showBackupPolicyDialog) {
        BackupPolicyDialog(
            currentPolicy = uiState.thread?.backupPolicy ?: BackupPolicy.GLOBAL,
            onPolicySelected = { policy ->
                onUpdateBackupPolicy(policy)
                showBackupPolicyDialog = false
            },
            onDismiss = { showBackupPolicyDialog = false }
        )
    }

    if (showDateRangePicker) {
        DateRangeBottomSheet(
            onSelect = { start, end ->
                onSelectByDateRange(start, end)
                showDateRangePicker = false
            },
            onDismiss = { showDateRangePicker = false }
        )
    }

    // ── Scaffold + overlay ────────────────────────────────────────────────────

    // Provide the current font scale to all bubble composables via CompositionLocal.
    // A two-finger pinch anywhere on the thread area updates the scale via onAdjustFontScale.
    CompositionLocalProvider(LocalBubbleFontScale provides bubbleFontScale) {
    Box(
        Modifier
            .fillMaxSize()
            // Detect two-finger pinch anywhere on the thread to adjust font scale.
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomFactor, _ ->
                    // zoomFactor > 1 = spreading (bigger), < 1 = pinching (smaller).
                    // Map zoom to a delta relative to default 1.0, dampened so a single
                    // gesture sweep doesn't jump the full range.
                    onAdjustFontScale((zoomFactor - 1f) * 0.5f)
                }
            }
    ) {
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // The date pill lives in the topBar slot (not the content Box) so it can
            // straddle the bar's bottom edge: Scaffold draws topBar above body content,
            // so the pill's overhanging half renders over the message list rather than
            // disappearing behind the bar.
            Box {
            val topBarMode = when {
                uiState.reactionPickerMessageId != null -> TopBarMode.ACTION
                uiState.isSelectionMode                 -> TopBarMode.SELECTION
                else                                    -> TopBarMode.NORMAL
            }
            // Written only while selection mode is live; read by the exiting
            // SELECTION branch below, which AnimatedContent keeps composing after
            // the selection state has already been cleared.
            val exitingSelection = remember { Retained(emptySet<Long>()) }
            if (topBarMode == TopBarMode.SELECTION) {
                exitingSelection.value = uiState.selectedMessageIds
            }
            AnimatedContent(
                targetState = topBarMode,
                transitionSpec = {
                    // The SizeTransform animates the selection bar's extra chip-row
                    // height in and out instead of jumping the list; clip = false so
                    // the taller exiting bar stays visible while it shrinks (the
                    // topBar slot already draws over the message list).
                    (fadeIn() + slideInVertically()) togetherWith
                        (fadeOut() + slideOutVertically()) using
                        SizeTransform(clip = false)
                },
                label = "topBarSwap"
            ) { mode ->
            when (mode) {
                TopBarMode.ACTION -> MessageActionTopBar(
                    onCancel  = { onDismissReactionPicker() },
                    onCopy    = {
                        val msg = uiState.messages.find { it.id == uiState.reactionPickerMessageId }
                        if (msg != null) {
                            val cb = context.getSystemService(ClipboardManager::class.java)
                            cb.setPrimaryClip(ClipData.newPlainText("message", msg.body))
                            Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
                        }
                        onDismissReactionPicker()
                    },
                    onSelect  = { onEnterSelectionModeFromActionMode() },
                    onForward = { uiState.reactionPickerMessageId?.let { onForwardMessage(it) } },
                    onDelete  = {
                        pendingDeleteMessageId = uiState.reactionPickerMessageId
                        onDismissReactionPicker()
                    }
                )
                TopBarMode.SELECTION -> SelectionTopBar(
                    // During the exit animation the live selection is already empty —
                    // render the retained in-mode value so the bar doesn't flash
                    // "0 selected" while sliding out.
                    selectedCount = (if (topBarMode == TopBarMode.SELECTION)
                        uiState.selectedMessageIds else exitingSelection.value).size,
                    totalMessages = uiState.messages.size,
                    scope = uiState.selectionScope,
                    onClose = { onExitSelectionMode() },
                    onScopeChange = { onSetSelectionScope(it) },
                    onShowDateRange = { showDateRangePicker = true },
                    onCopy = {
                        // Drop taps on the exiting bar: the live selection is empty,
                        // so copying would clobber the clipboard with an empty export
                        // (pre-animation the bar vanished the same frame and was
                        // untappable in this window).
                        if (topBarMode != TopBarMode.SELECTION) return@SelectionTopBar
                        val text = ExportFormatter.formatForCopy(
                            uiState.messages.filter { it.id in uiState.selectedMessageIds },
                            uiState.thread?.let { t -> t.nickname ?: t.displayName } ?: "",
                            "",
                            uiState.thread?.address ?: ""
                        )
                        val cb = context.getSystemService(ClipboardManager::class.java)
                        cb.setPrimaryClip(ClipData.newPlainText("Postmark export", text))
                        scope.launch {
                            snackbarHostState.showSnackbar("Copied", duration = SnackbarDuration.Short)
                        }
                        onExitSelectionMode()
                    }
                )
                TopBarMode.NORMAL -> TopAppBar(
                    title = {
                        val name = uiState.thread?.let { t -> t.nickname ?: formatPhoneNumber(t.displayName) } ?: ""
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.clickable(onClick = onViewContact)
                        ) {
                            ContactAvatar(
                                address = uiState.thread?.address ?: "",
                                name = name,
                                size = 36.dp
                            )
                            Text(name)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, "More options")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("View stats") },
                                    onClick = { menuExpanded = false; onViewStats() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Select messages") },
                                    onClick = { menuExpanded = false; onEnterSelectionMode() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Search in thread") },
                                    onClick = { menuExpanded = false; onSearchInThread() }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (uiState.thread?.isMuted == true) "Unmute" else "Mute") },
                                    onClick = { menuExpanded = false; onToggleMute() }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (uiState.thread?.notificationsEnabled == false) "Enable notifications" else "Disable notifications") },
                                    onClick = { menuExpanded = false; onToggleNotifications() }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (uiState.thread?.isPinned == true) "Unpin" else "Pin") },
                                    onClick = { menuExpanded = false; onTogglePin() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Backup settings") },
                                    onClick = { menuExpanded = false; showBackupPolicyDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reset text size") },
                                    onClick = { menuExpanded = false; onResetFontScale() }
                                )
                                // Hidden for group threads — "the number" is ambiguous when
                                // the thread has multiple participants.
                                if ((uiState.thread?.participants?.size ?: 0) <= 1) {
                                    DropdownMenuItem(
                                        text = { Text("Block number") },
                                        onClick = { menuExpanded = false; showBlockConfirmDialog = true }
                                    )
                                }
                            }
                        }
                    }
                )
            }
            }
            // Half-in, half-out of the top bar: bottom-anchored to the bar, then pushed
            // down by half its own height at draw time. graphicsLayer keeps the shift
            // out of layout, so the pill never changes the measured topBar height (and
            // hit testing follows the layer, so the overhanging half stays tappable).
            // Messages deliberately scroll behind the overhang — the list reserves no
            // space for it.
            FloatingDatePill(
                dateLabel = pillDateLabel,
                visible = pillDateLabel.isNotEmpty(),
                onClick = { showCalendarPicker = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationY = size.height / 2f }
            )
            }
        },
        bottomBar = {
            // Keep ReplyBar in layout even when picker is open (alpha=0) so the
            // Scaffold doesn't resize and shift message positions.
            if (!uiState.isSelectionMode) {
                ReplyBar(
                    text                  = uiState.replyText,
                    pendingAttachments    = uiState.pendingAttachments,
                    // O(1) via the prebuilt index — a linear find here ran per keystroke
                    // (this parameter re-evaluates every ThreadContent recomposition).
                    replyingTo            = uiState.replyingToId?.let { id ->
                        uiState.renderState.messageIdToIndex[id]
                            ?.let { uiState.renderState.items.getOrNull(it) as? ThreadListItem.Bubble }
                            ?.message
                    },
                    // Sending is still 1:1-only (MMS_AUDIT #6) — thread.address, not the
                    // full roster. Warn rather than silently reply to just one participant.
                    isGroupThread         = (uiState.thread?.participants?.size ?: 0) > 1,
                    onTextChange          = { onReplyTextChanged(it) },
                    onAttachmentsSelected = onAttachmentsSelected,
                    onRemoveAttachment    = onRemoveAttachment,
                    onClearReplyingTo     = onClearReplyingTo,
                    onSend                = { onSendMessage() },
                    voiceMemo             = voiceMemo,
                    onVoiceMemoEvent      = onVoiceMemoEvent,
                    recordingLevel        = recordingLevel,
                    audioPlayback         = audioPlayback,
                    memoWaveforms         = memoWaveforms,
                    onAudioPlayPause      = onAudioPlayPause,
                    onAudioSeek           = onAudioSeek
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Flat list from renderState — no forEach loops, stable keys, no recomputation.
                // contentType lets LazyColumn reuse composition slots per item kind instead
                // of pairing a disposed DateHeader slot with an incoming Bubble during fling.
                items(
                    uiState.renderState.items,
                    key = { it.key },
                    contentType = { it is ThreadListItem.Bubble }
                ) { item ->
                    // animateItem on both item kinds: a new message (and the date header
                    // that sometimes arrives with it) fades in and slides neighbours
                    // apart instead of popping. Placement cost during a bulk-sync burst
                    // of inserts needs an on-device check in a large thread.
                    when (item) {
                        is ThreadListItem.Bubble -> MessageBubble(
                            message = item.message,
                            modifier = Modifier.animateItem(),
                            clusterPosition = item.clusterPosition,
                            isSelected = item.message.id in uiState.selectedMessageIds,
                            isSelectionMode = uiState.isSelectionMode,
                            isHighlighted = item.message.id == uiState.highlightedMessageId,
                            onToggleSelect = { onToggleSelection(item.message.id) },
                            onImageTap = onImageTap,
                            onVideoTap = onVideoTap,
                            onLongClick = { y -> onShowReactionPicker(item.message.id, y) },
                            onReactionTargetYChanged = if (item.message.id == uiState.reactionPickerMessageId)
                                { y -> liveBubbleY = y } else null,
                            onReactionClick = { emoji -> onToggleReaction(item.message.id, emoji) },
                            timestampPref = timestampPref,
                            isTimestampExpanded = item.message.id in uiState.expandedTimestampIds,
                            onToggleTimestamp = { onToggleTimestamp(item.message.id) },
                            onRetry = { onRetry(item.message.id) },
                            // Swipe-to-reply: disabled while in selection mode so the
                            // horizontal drag doesn't conflict with checkboxes.
                            onSwipeToReply = if (!uiState.isSelectionMode)
                                { -> onSetReplyingTo(item.message.id) } else null,
                            // Group threads only: label the first received bubble of each
                            // sender's cluster. Roster misses (address formatting drift)
                            // fall back to the formatted number rather than no label.
                            senderName = if (
                                participantNames.isNotEmpty() &&
                                !item.message.isSent &&
                                (item.clusterPosition == ClusterPosition.TOP ||
                                    item.clusterPosition == ClusterPosition.SINGLE)
                            ) {
                                participantNames[item.message.address]
                                    ?: formatPhoneNumber(item.message.address)
                            } else null,
                            audioPlayback = audioPlayback,
                            onAudioPlayPause = onAudioPlayPause,
                            onAudioSeek = onAudioSeek
                        )
                        is ThreadListItem.DateHeader -> DateHeader(
                            label = item.dateLabel,
                            modifier = Modifier.animateItem(),
                            isSelectionMode = uiState.isSelectionMode,
                            selectedCount = item.messageIds.count { it in uiState.selectedMessageIds },
                            totalCount = item.messageIds.size,
                            onToggleDay = { onToggleMessageIds(item.messageIds) }
                        )
                    }
                }
            }

            // Full-screen image viewer — pages across every image in the thread, not just
            // the tapped message's own attachments. Rendered once here (not per-bubble) so
            // there's a single shared pager instance keyed by a thread-wide index.
            globalImageViewerIndex?.let { startIndex ->
                if (uiState.threadImages.isNotEmpty()) {
                    FullScreenImageViewer(
                        images = uiState.threadImages,
                        initialIndex = startIndex,
                        contactDisplayName = contactDisplayName,
                        navBarBottomPadding = navBarBottomPadding,
                        quickReactionEmojis = quickReactionEmojis,
                        onToggleReaction = onToggleReaction,
                        onToggleStarred = onToggleStarred,
                        onJumpToMessage = { messageId ->
                            globalImageViewerIndex = null
                            scope.launch { scrollToMessageCentered(messageId) }
                        },
                        onDeleteRequest = { messageId ->
                            globalImageViewerIndex = null
                            pendingDeleteMessageId = messageId
                        },
                        onForward = { messageId ->
                            globalImageViewerIndex = null
                            onForwardMessage(messageId)
                        },
                        onDismiss = { globalImageViewerIndex = null }
                    )
                }
            }

            // Full-screen video player — hosted here (not per-bubble) so it survives the
            // LazyColumn item that launched it being disposed during a rotation relayout.
            playingVideoUri?.let { videoUri ->
                VideoPlayerDialog(
                    uri = videoUri,
                    onDismiss = { playingVideoUri = null }
                )
            }

            // Delete confirmation — shared by the action-bar Delete button and the image
            // viewer's trash icon. Deletion is real (removes the system content://sms/mms
            // row, not just Postmark's copy), so both entry points confirm here rather than
            // deleting on tap.
            pendingDeleteMessageId?.let { messageId ->
                AlertDialog(
                    onDismissRequest = { pendingDeleteMessageId = null },
                    title = { Text("Delete message?") },
                    text = { Text("This removes it from this device's SMS/MMS history. This can't be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            onDeleteMessage(messageId)
                            pendingDeleteMessageId = null
                        }) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteMessageId = null }) { Text("Cancel") }
                    }
                )
            }

            // Auto-hide the FAB 3 s after the user stops scrolling.
            // fabVisible is hoisted to the outer scope so the message-arrival
            // effect above can also trigger it when the user is reading history.
            // snapshotFlow keeps the per-frame scroll-offset reads out of the
            // composition phase (as LaunchedEffect keys they recomposed this scope
            // and restarted the coroutine on every scrolled frame); collectLatest
            // reproduces the old restart-the-countdown-per-scroll-tick semantics.
            LaunchedEffect(listState) {
                snapshotFlow {
                    listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                }.collectLatest { (index, _) ->
                    if (index > 0) {
                        fabVisible = true
                        delay(3_000)
                    }
                    fabVisible = false
                }
            }

            ScrollToLatestButton(
                visible = fabVisible,
                onClick = {
                    fabVisible = false
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
        }
    }

    // ── Emoji reaction popup (overlays full screen including action bar) ─────────────────

        val reactionPickerMessage = uiState.reactionPickerMessageId?.let { id ->
            uiState.messages.find { it.id == id }
        }
        reactionPickerMessage?.let { msg ->
            EmojiReactionPopup(
                message        = msg,
                quickEmojis    = quickReactionEmojis,
                bubbleBottomY  = liveBubbleY,
                onReact     = { emoji -> onToggleReaction(msg.id, emoji) },
                onDismiss   = { onDismissReactionPicker() }
            )
        }
    } // end overlay Box
    } // end CompositionLocalProvider
}

// ── SelectionTopBar ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    totalMessages: Int,
    scope: SelectionScope,
    onClose: () -> Unit,
    onScopeChange: (SelectionScope) -> Unit,
    onShowDateRange: () -> Unit,
    onCopy: () -> Unit
) {
    // "All" chip doubles as a deselect-all affordance: when every message is
    // selected it shows "None" so the user has a clear way to clear the selection.
    val allSelected = totalMessages > 0 && selectedCount == totalMessages

    Column {
        TopAppBar(
            title = { Text("$selectedCount selected") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Cancel selection")
                }
            },
            actions = {
                IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Copy") }
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = scope == SelectionScope.MESSAGES,
                onClick  = { onScopeChange(SelectionScope.MESSAGES) },
                label    = { Text("Messages") }
            )
            FilterChip(
                selected = allSelected,
                onClick  = { onScopeChange(SelectionScope.ALL) },
                label    = { Text("All") }
            )
            FilterChip(
                selected = false,
                onClick  = { onShowDateRange() },
                label    = { Text("Date range") }
            )
        }
    }
}

// DateRangeBottomSheet moved to ui/components/DateRangeSheet.kt — shared with the
// backup Export screen.

// ── ScrollToLatestButton ─────────────────────────────────────────────────────────

@Composable
private fun ScrollToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(150)),
        exit  = fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ) {
            Icon(Icons.Default.VerticalAlignBottom, contentDescription = "Scroll to latest")
        }
    }
}

// ── FloatingDatePill ───────────────────────────────────────────────────────────

@Composable
private fun FloatingDatePill(
    dateLabel: String,
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(150)),
        exit  = fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp
        ) {
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

// ── MessageBubble ──────────────────────────────────────────────────────────────

/**
 * Renders a single message bubble with optional reaction pills below it.
 *
 * Tap behaviour depends on the current mode:
 *  - Normal mode + ON_TAP timestamp pref  → toggles the timestamp
 *  - Selection mode                        → toggles this message's selected state
 *  - Long press (normal mode only)         → opens the emoji reaction popup
 *
 * Cluster-aware: [clusterPosition] controls which corners are rounded/flat so that
 * consecutive same-sender messages appear visually joined.
 *
 * @param message              The message to render.
 * @param clusterPosition      Position within a run of same-sender messages.
 * @param isSelected           Whether this bubble is part of the current selection.
 * @param isSelectionMode      Whether the screen is in multi-select mode.
 * @param isHighlighted        True when arriving from a search result (tertiaryContainer tint).
 * @param onToggleSelect       Called when the bubble is tapped in selection mode.
 * @param onLongClick          Called with the bubble's root-Y (pixels) on long press.
 * @param onReactionClick      Called with the emoji string when a reaction pill is tapped.
 * @param timestampPref        Global user preference for when timestamps are shown.
 * @param isTimestampExpanded  Whether the timestamp is currently revealed (ON_TAP mode).
 * @param onToggleTimestamp    Called when a tap should toggle the timestamp.
 * @param onRetry              Called when the user taps the failed-send indicator to retry.
 * @param onSwipeToReply       When non-null, a right swipe past the threshold triggers this to
 *                             quote the message in the reply bar. Null disables the gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    clusterPosition: ClusterPosition,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isHighlighted: Boolean,
    onToggleSelect: () -> Unit,
    onLongClick: (bubbleTopY: Float) -> Unit,
    // When non-null, fires on every layout pass with the bubble's current positionInRoot().y.
    // Used by ThreadContent to keep liveBubbleY in sync so EmojiReactionPopup tracks the
    // bubble even after top-bar swaps or IME dismissals change the layout.
    onReactionTargetYChanged: ((Float) -> Unit)? = null,
    // Tapping an image attachment reports its URI up to ThreadContent, which owns the
    // single shared full-screen viewer and resolves the URI to a thread-wide page index.
    onImageTap: (String) -> Unit = {},
    // Tapping a video reports its URI up to ThreadContent, which owns the single shared
    // player dialog. Hosted there (not per-bubble) so it survives the LazyColumn item
    // being disposed during a rotation relayout — otherwise rotating dumps back to chat.
    onVideoTap: (String) -> Unit = {},
    onReactionClick: (String) -> Unit,
    timestampPref: TimestampPreference,
    isTimestampExpanded: Boolean,
    onToggleTimestamp: () -> Unit,
    onRetry: () -> Unit = {},
    // Null = gesture disabled (e.g. while in selection mode).
    onSwipeToReply: (() -> Unit)? = null,
    // Non-null only in group threads, for the first received bubble of a sender's
    // cluster — rendered as a small name label above the bubble so participants
    // are distinguishable (they otherwise all render identically to a 1:1 thread).
    senderName: String? = null,
    // Shared thread-wide audio player (perf-analysis #30) — audio chips collect the
    // flow themselves so position ticks never recompose the bubble.
    audioPlayback: StateFlow<AudioPlaybackState> = MutableStateFlow(AudioPlaybackState()),
    onAudioPlayPause: (String) -> Unit = {},
    onAudioSeek: (String, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val baseBubbleColor = if (message.isSent)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    // Search-jump / "Go to chat" highlight. The ViewModel drops highlightedMessageId
    // 2 s after the jump; animating the colour turns that hard flip into a quick
    // tint-in and a gentle fade back to the resting bubble colour.
    val bubbleColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.tertiaryContainer
                      else baseBubbleColor,
        animationSpec = tween(durationMillis = if (isHighlighted) 150 else 600),
        label = "bubbleHighlight"
    )

    val alignment = if (message.isSent) Alignment.End else Alignment.Start

    // Timestamps: with ALWAYS pref, only show at the tail of a cluster (SINGLE or BOTTOM).
    val showTimestamp = when (timestampPref) {
        TimestampPreference.ALWAYS ->
            clusterPosition == ClusterPosition.SINGLE || clusterPosition == ClusterPosition.BOTTOM
        TimestampPreference.ON_TAP -> isTimestampExpanded
        TimestampPreference.NEVER  -> false
    }

    // Tighter vertical padding between siblings in the same cluster.
    val topPadding    = if (clusterPosition == ClusterPosition.BOTTOM ||
                            clusterPosition == ClusterPosition.MIDDLE) 1.dp else 2.dp
    val bottomPadding = if (clusterPosition == ClusterPosition.TOP    ||
                            clusterPosition == ClusterPosition.MIDDLE) 1.dp else 2.dp

    val bubbleRootY = remember { FloatArray(1) }
    val density = LocalDensity.current

    // ── Swipe-to-reply gesture state ──────────────────────────────────────────
    // Animatable allows smooth spring-back after the user releases or crosses threshold.
    val swipeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val maxDragPx  = with(density) { 72.dp.toPx() }   // hard cap on drag distance
    val thresholdPx = with(density) { 56.dp.toPx() }  // crossing this fires onSwipeToReply

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                // Track the bottom edge of the bubble — popup is placed just below it.
                val y = coords.positionInRoot().y + coords.size.height.toFloat()
                bubbleRootY[0] = y
                // If this bubble is the current reaction target, notify ThreadContent
                // so liveBubbleY stays in sync with the bubble's real screen position.
                onReactionTargetYChanged?.invoke(y)
            }
            .combinedClickable(
                onClick = {
                    when {
                        isSelectionMode -> onToggleSelect()
                        timestampPref == TimestampPreference.ON_TAP -> onToggleTimestamp()
                    }
                },
                onLongClick = {
                    if (!isSelectionMode) onLongClick(bubbleRootY[0])
                }
            )
            // Horizontal drag gesture for swipe-to-reply (only right direction).
            .then(
                if (onSwipeToReply != null) Modifier.pointerInput(onSwipeToReply) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeOffset.value >= thresholdPx) onSwipeToReply()
                            coroutineScope.launch {
                                swipeOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                swipeOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            // Only allow rightward drag (positive X); ignore leftward.
                            val newVal = (swipeOffset.value + dragAmount).coerceIn(0f, maxDragPx)
                            coroutineScope.launch { swipeOffset.snapTo(newVal) }
                        }
                    )
                } else Modifier
            )
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier
            )
            .padding(start = 12.dp, end = 12.dp, top = topPadding, bottom = bottomPadding),
        horizontalAlignment = alignment
    ) {
        if (senderName != null) {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        // Wrap the bubble in a full-width Box so the reply icon can be positioned on the
        // opposite side while the bubble itself translates horizontally on swipe.
        Box(modifier = Modifier.fillMaxWidth()) {
            // Reply icon: fades in as the bubble is dragged right, sits on the start edge.
            if (onSwipeToReply != null) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        // Lambda graphicsLayer defers the Animatable read to the draw
                        // phase — Modifier.alpha() read it in composition, recomposing
                        // the whole bubble on every drag/spring-back frame.
                        .graphicsLayer { alpha = (swipeOffset.value / thresholdPx).coerceIn(0f, 1f) }
                )
            }
            // Bubble content — translated right during swipe, springs back on release.
            // A Column rather than a Box overlay so the reaction pills take part in
            // layout: they report only their bottom half (see straddle modifier below),
            // so the space they hang into below the bubble is reserved and the
            // timestamp row / next message are pushed down instead of collided with.
            // End-aligned because the pills anchor to the bubble's bottom-end corner
            // for both sent and received; a pill row wider than a short bubble grows
            // leftward past its edge instead of pushing the bubble around.
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .align(if (message.isSent) Alignment.CenterEnd else Alignment.CenterStart)
                    .graphicsLayer { translationX = swipeOffset.value },
                horizontalAlignment = Alignment.End
            ) {
            Box(
                modifier = Modifier
                    .background(bubbleColor, bubbleShape(message.isSent, clusterPosition))
                    .then(
                        // Tighter padding when an attachment fills the bubble edges.
                        if (message.attachments.isNotEmpty())
                            Modifier.padding(4.dp)
                        else
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
            ) {
                if (message.attachments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (message.attachments.size == 1) {
                            // Single attachment — full-width rendering (image, video, or audio).
                            val att = message.attachments[0]
                            MmsAttachment(
                                uri = att.uri,
                                mimeType = att.mimeType,
                                // Only images are tappable; video/audio have their own interactions.
                                onImageClick = if (att.mimeType.startsWith("image/"))
                                    { { onImageTap(att.uri) } } else null,
                                onVideoClick = if (att.mimeType.startsWith("video/"))
                                    { { onVideoTap(att.uri) } } else null,
                                audioPlayback = audioPlayback,
                                onAudioPlayPause = onAudioPlayPause,
                                onAudioSeek = onAudioSeek
                            )
                        } else {
                            // Multiple attachments — 2-column thumbnail grid for images/videos;
                            // audio and unknown types render full-width below the grid.
                            val (gridable, others) = message.attachments.partition {
                                it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")
                            }
                            gridable.chunked(2).forEach { rowItems ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    rowItems.forEach { att ->
                                        AttachmentThumbnail(
                                            attachment = att,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                if (att.mimeType.startsWith("image/")) {
                                                    onImageTap(att.uri)
                                                } else {
                                                    onVideoTap(att.uri)
                                                }
                                            }
                                        )
                                    }
                                    // Keep a lone last cell at half width.
                                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            others.forEach { att ->
                                MmsAttachment(
                                    uri = att.uri,
                                    mimeType = att.mimeType,
                                    audioPlayback = audioPlayback,
                                    onAudioPlayPause = onAudioPlayPause,
                                    onAudioSeek = onAudioSeek
                                )
                            }
                        }
                        // Show caption text below the attachment if present.
                        if (message.body.isNotEmpty()) {
                            val fontScale   = LocalBubbleFontScale.current
                            val linkColor   = MaterialTheme.colorScheme.primary
                            val textColor   = LocalContentColor.current
                            val baseStyle   = MaterialTheme.typography.bodyMedium
                            val ctx         = LocalContext.current
                            val annotated   = remember(message.body, linkColor, ctx) {
                                linkifyText(message.body, linkColor, ctx)
                            }
                            Text(
                                text = annotated,
                                style = baseStyle.copy(
                                    fontSize = baseStyle.fontSize * fontScale,
                                    color = textColor
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                } else {
                    // Plain SMS bubble — linkify URLs and phone numbers.
                    val fontScale  = LocalBubbleFontScale.current
                    val linkColor  = MaterialTheme.colorScheme.primary
                    val textColor  = LocalContentColor.current
                    val baseStyle  = MaterialTheme.typography.bodyMedium
                    val ctx        = LocalContext.current
                    val annotated  = remember(message.body, linkColor, ctx) {
                        linkifyText(message.body, linkColor, ctx)
                    }
                    Text(
                        text = annotated,
                        style = baseStyle.copy(
                            fontSize = baseStyle.fontSize * fontScale,
                            color = textColor
                        )
                    )
                }
            }
            if (message.reactions.isNotEmpty()) {
                // Google Messages-style badge straddling the bubble's bottom-end corner,
                // half in / half out — same treatment as the date pill on the top bar.
                // The top half draws over the bubble; how much space the bottom half
                // reserves below (all derived from the measured pill height) depends on
                // what follows:
                //  - Sent: the timestamp/status row shares the pill's corner, so reserve
                //    the overhang minus the row's own top whitespace (2dp padding +
                //    label line-height leading) — the row tucks that whitespace under
                //    the pill and the visible gap stays tight.
                //  - Received with a timestamp row: the row sits on the opposite corner
                //    and already clears the overhang — reserve nothing.
                //  - Received without a row (mid-cluster, timestamps hidden): reserve
                //    the full overhang to keep the pill off the next message.
                ReactionPills(
                    reactions = message.reactions,
                    onReactionClick = onReactionClick,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val overhang = placeable.height / 2
                            val reserved = when {
                                message.isSent ->
                                    (placeable.height - overhang - 6.dp.roundToPx())
                                        .coerceAtLeast(0)
                                showTimestamp -> 0
                                else -> placeable.height - overhang
                            }
                            layout(placeable.width, reserved) {
                                placeable.place(0, -overhang)
                            }
                        }
                        .offset(x = 6.dp)  // nudge past the corner horizontally
                )
            }
        }  // end Column(widthIn+align)
        }  // end Box(fillMaxWidth) swipe wrapper
        if (showTimestamp || message.isSent) {
            Row(
                modifier = Modifier
                    .padding(
                        start  = if (!message.isSent) 4.dp else 0.dp,
                        end    = if (message.isSent)  4.dp else 0.dp,
                        top    = 2.dp,
                        bottom = 2.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (showTimestamp) {
                    // SMS / MMS type label — helps when scrolling back into pre-RCS history.
                    Text(
                        text = if (message.isMms) "MMS" else "SMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                    Text(
                        text = remember(message.timestamp) {
                            formatEpochMillis(message.timestamp, timeFormatter)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (message.isSent) {
                    DeliveryStatusIndicator(
                        status = message.deliveryStatus,
                        onRetry = onRetry,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
    }
}

// ── AttachmentThumbnail ───────────────────────────────────────────────────────
// One square cell of the multi-attachment grid inside a bubble: image thumbnail
// (Coil) or a play-icon placeholder for video. Tap behaviour is supplied by the
// caller (open the paged viewer for images, the player dialog for videos).

@Composable
private fun AttachmentThumbnail(
    attachment: MessageAttachment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (attachment.mimeType.startsWith("image/")) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(Uri.parse(attachment.uri))
                    // Grid cells are at most half the bubble width (~140dp); 280px
                    // covers 2× density without decoding full-resolution bitmaps.
                    .size(280, 280)
                    .crossfade(true)
                    .build(),
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Video cell — cached first frame (see rememberVideoThumbnail) with a play badge.
            val videoThumb = rememberVideoThumbnail(attachment.uri)
            videoThumb?.let {
                Image(
                    bitmap             = it.asImageBitmap(),
                    contentDescription = "Video preview",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    modifier = Modifier.size(28.dp),
                    tint = Color.White
                )
            }
        }
    }
}

// ── MmsAttachment ─────────────────────────────────────────────────────────────
// Renders the media content of an MMS message inside the bubble. Images use
// Coil AsyncImage (content://mms/part/ URIs are readable by the default SMS app).
// Video shows a play-icon placeholder. Audio shows a labelled chip.

@Composable
private fun MmsAttachment(
    uri: String,
    mimeType: String?,
    // Non-null when the image can be tapped to open the full-screen viewer.
    onImageClick: (() -> Unit)? = null,
    // Non-null when the video thumbnail can be tapped to open the player dialog.
    onVideoClick: (() -> Unit)? = null,
    // Shared thread-wide audio player (perf-analysis #30); only the audio branch uses it.
    audioPlayback: StateFlow<AudioPlaybackState> = MutableStateFlow(AudioPlaybackState()),
    onAudioPlayPause: (String) -> Unit = {},
    onAudioSeek: (String, Float) -> Unit = { _, _ -> }
) {
    when {
        // ── Image ──────────────────────────────────────────────────────────────
        mimeType?.startsWith("image/") == true -> {
            // Use ImageRequest.Builder with explicit context so Coil's
            // ContentUriFetcher can call contentResolver.openInputStream()
            // on the content://mms/part/ URI (requires default SMS role).
            val ctx = LocalContext.current
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(Uri.parse(uri))
                    // Bound the decoded bitmap to 2× the bubble's max display size (280dp × 240dp).
                    // Coil will not decode a larger bitmap than this, cutting memory use and
                    // decode time significantly for full-resolution camera images.
                    .size(560, 480)
                    .crossfade(true)
                    .listener(onError = { _, result ->
                        android.util.Log.e(
                            "CoilMMS",
                            "Failed to load uri=$uri",
                            result.throwable
                        )
                    })
                    .build(),
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                    )
                },
                error = {
                    // Visible fallback so load failures don't silently disappear.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "📷 Photo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (onImageClick != null)
                            Modifier.clickable(onClick = onImageClick)
                        else Modifier
                    )
            )
        }

        // ── Video ──────────────────────────────────────────────────────────────
        mimeType?.startsWith("video/") == true -> {
            // Cached first frame (see rememberVideoThumbnail) so the bubble shows a real
            // still; falls back to the play-icon placeholder while loading or on failure.
            val videoThumb = rememberVideoThumbnail(uri)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (onVideoClick != null) Modifier.clickable(onClick = onVideoClick)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                videoThumb?.let {
                    Image(
                        bitmap             = it.asImageBitmap(),
                        contentDescription = "Video preview",
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize()
                    )
                }
                // Play badge overlaid at centre — signals it's a video and gives a clear
                // tap target even before (or if) the thumbnail resolves.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        modifier = Modifier.size(32.dp),
                        tint = Color.White
                    )
                }
            }
        }

        // ── Audio ──────────────────────────────────────────────────────────────
        mimeType?.startsWith("audio/") == true -> {
            AudioChip(
                uri = uri,
                audioPlayback = audioPlayback,
                onPlayPause = onAudioPlayPause,
                onSeek = onAudioSeek,
                // One metadata read per visible chip, once ever per uri (cached), so the
                // real length shows before first play; scrolling past again is free.
                fallbackDurationMs = rememberAudioDurationMs(uri)
            )
        }

        // ── Unknown attachment ─────────────────────────────────────────────────
        else -> {
            Text(
                text = "[Attachment]",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── AudioChip ──────────────────────────────────────────────────────────────────

/**
 * Play/seek chip for an audio attachment, driven entirely by the single
 * ViewModel-owned player (perf-analysis #30 — replaced a per-chip raw MediaPlayer;
 * two chips can no longer play at once, and playback survives the chip scrolling
 * off-screen because nothing player-related is tied to this composition).
 *
 * Collects [audioPlayback] itself (rather than taking a value) so the ~5 Hz position
 * ticks recompose only audio chips, never the bubbles above them.
 */
@Composable
private fun AudioChip(
    uri: String,
    audioPlayback: StateFlow<AudioPlaybackState>,
    onPlayPause: (String) -> Unit,
    onSeek: (String, Float) -> Unit,
    // Duration read from file metadata, for chips that must show a real length
    // before the player has ever loaded this uri (pending-memo review row).
    // Bubbles pass nothing and keep the lazy "Voice memo" label until first play.
    fallbackDurationMs: Long? = null,
    // Real amplitude waveform for recorded memos (reply-bar chips only). Non-empty →
    // replaces the Slider with a WaveformScrubber; null/empty → Slider (bubbles, and
    // any chip whose map entry is missing — e.g. an exit-animation stale render).
    waveform: List<Float>? = null
) {
    val playback by audioPlayback.collectAsState()
    val isCurrent  = playback.uri == uri
    val isPlaying  = isCurrent && playback.isPlaying
    val isLoading  = isCurrent && playback.isLoading
    // Player duration drives position math; the fallback only labels the chip.
    val durationMs = if (isCurrent) playback.durationMs else 0L
    val displayDurationMs = if (durationMs > 0) durationMs else (fallbackDurationMs ?: 0L)

    /* Non-null only mid-drag: the thumb follows the finger instead of the player's
     * position ticks, then commits the seek on release. */
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val position = scrubFraction
        ?: if (durationMs > 0) (playback.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { if (!isLoading) onPlayPause(uri) },
                modifier = Modifier.size(36.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                /* Seeking only applies to the loaded item — a chip that isn't playing
                 * has nothing to seek, so its scrubber/slider is inert at 0. Recorded
                 * memos with captured amplitudes get the waveform; everything else (and
                 * any missing/empty entry) keeps the Slider. */
                if (waveform != null && waveform.isNotEmpty()) {
                    WaveformScrubber(
                        samples          = waveform,
                        positionFraction = position,
                        enabled          = isCurrent && durationMs > 0,
                        onScrub          = { scrubFraction = it },
                        onScrubFinished  = {
                            scrubFraction?.let { onSeek(uri, it) }
                            scrubFraction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Slider(
                        value    = position,
                        onValueChange = { scrubFraction = it },
                        onValueChangeFinished = {
                            scrubFraction?.let { onSeek(uri, it) }
                            scrubFraction = null
                        },
                        enabled  = isCurrent && durationMs > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor         = MaterialTheme.colorScheme.onSecondaryContainer,
                            activeTrackColor   = MaterialTheme.colorScheme.onSecondaryContainer,
                            inactiveTrackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.3f),
                            disabledThumbColor         = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                            disabledActiveTrackColor   = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                            disabledInactiveTrackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.3f)
                        )
                    )
                }
                // Elapsed time (left) and total duration (right).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text  = if (durationMs > 0) formatMemoDuration((position * durationMs).toLong()) else "0:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        // Show total duration once known (from the player or the
                        // metadata fallback); "Voice memo" until first play otherwise.
                        text  = if (displayDurationMs > 0) formatMemoDuration(displayDurationMs)
                                else "Voice memo",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

// ── WaveformScrubber ─────────────────────────────────────────────────────────

/**
 * Google-Messages-style amplitude waveform that doubles as the seek control for a
 * recorded memo chip (reply-bar preview + pending strip only — bubbles keep the
 * Slider; see the compose-gesture-conflict rule, these detectors must never sit on
 * or over a message bubble).
 *
 * Fed values, never a collector: [positionFraction] follows the finger mid-drag
 * (the caller passes its scrub value) and the player's position otherwise, so the
 * bar split never freezes on a conflated flow.
 *
 * @param positionFraction 0..1 played/unplayed split point.
 * @param onScrub          finger-follow — set the caller's scrubFraction.
 * @param onScrubFinished  commit — seek + clear scrubFraction.
 */
@Composable
private fun WaveformScrubber(
    samples: List<Float>,
    positionFraction: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Colors read OUTSIDE the draw lambda (MaterialTheme can't be read inside a
    // DrawScope). Unplayed is always the 0.3-alpha track; played is full-strength
    // when enabled, 0.5-alpha when not — matching the Slider's disabled colors.
    val base          = MaterialTheme.colorScheme.onSecondaryContainer
    val playedColor   = if (enabled) base else base.copy(alpha = 0.5f)
    val unplayedColor = base.copy(alpha = 0.3f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            // TalkBack reads the position; scrub-by-accessibility-action isn't
            // supported (play/pause remains — accepted).
            .progressSemantics(positionFraction)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { onScrub(it.x / size.width); onScrubFinished() }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { onScrub((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd   = { onScrubFinished() },
                    onDragCancel = { onScrubFinished() }
                ) { change, _ ->
                    onScrub((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        // All geometry derived from the canvas size and sample count — no raw pixels.
        val slotWidth    = size.width / samples.size
        val barWidth     = slotWidth * 0.6f
        val minBarHeight = 3.dp.toPx()
        val radius       = CornerRadius(barWidth / 2f, barWidth / 2f)
        val cutoff       = positionFraction * size.width
        samples.forEachIndexed { index, sample ->
            val barHeight = maxOf(minBarHeight, sample.coerceIn(0f, 1f) * size.height)
            val barCenter = index * slotWidth + slotWidth / 2f
            drawRoundRect(
                color        = if (barCenter <= cutoff) playedColor else unplayedColor,
                topLeft      = Offset(index * slotWidth + (slotWidth - barWidth) / 2f, (size.height - barHeight) / 2f),
                size         = Size(barWidth, barHeight),
                cornerRadius = radius
            )
        }
    }
}

// ── DateHeader ─────────────────────────────────────────────────────────────────

@Composable
private fun DateHeader(
    label: String,
    isSelectionMode: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onToggleDay: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Three-state icon: none / partial / all selected for this day.
    val selectionIcon = when {
        !isSelectionMode         -> null
        selectedCount == 0       -> Icons.Default.RadioButtonUnchecked
        selectedCount == totalCount -> Icons.Default.CheckCircle
        else                     -> Icons.Default.IndeterminateCheckBox
    }
    val iconTint = if (selectedCount > 0)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionIcon != null) {
            IconButton(
                onClick  = onToggleDay,
                enabled  = isSelectionMode,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = selectionIcon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

// ── CalendarPickerDialog ───────────────────────────────────────────────────────

@Composable
private fun CalendarPickerDialog(
    activeDates: Set<LocalDate>,
    onDateSelected: (scrollTo: LocalDate, wasSnapped: Boolean, tappedLabel: String) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultMonth = activeDates.maxOrNull()?.let { YearMonth.from(it) } ?: YearMonth.now()
    var month by remember { mutableStateOf(defaultMonth) }

    val monthFormatter   = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    val tapLabelFormatter = remember { DateTimeFormatter.ofPattern("MMMM d") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.KeyboardArrowLeft, "Previous month")
                    }
                    Text(month.format(monthFormatter), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.KeyboardArrowRight, "Next month")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                        Text(
                            text = it,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                val firstDay    = month.atDay(1)
                val offset      = firstDay.dayOfWeek.value - 1  // Mon=0 … Sun=6
                val daysInMonth = month.lengthOfMonth()

                (0 until 42).chunked(7).forEach { weekCells ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        weekCells.forEach { cell ->
                            val dayNum = cell - offset + 1
                            val date   = if (dayNum in 1..daysInMonth) month.atDay(dayNum) else null
                            CalendarDayCell(
                                date = date,
                                isActive = date != null && date in activeDates,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (date == null) return@CalendarDayCell
                                    val tappedLabel = date.format(tapLabelFormatter)
                                    if (date in activeDates) {
                                        onDateSelected(date, false, tappedLabel)
                                    } else {
                                        val nearest = findNearestActiveDate(date, activeDates)
                                        if (nearest != null) onDateSelected(nearest, true, tappedLabel)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate?,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .then(if (date != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (date != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text  = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
                if (isActive) {
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

// ── DeliveryStatusIndicator ────────────────────────────────────────────────────

@Composable
private fun DeliveryStatusIndicator(
    status: Int,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Colored ticks: amber = sent to carrier, green = delivered to device, red = failed.
    // The bright shades read well on the dark theme but fail contrast on the light theme's
    // near-white background (the ticks render beside the timestamp, not on the bubble), so
    // pick darker shades when the surface is light.
    val lightTheme     = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val sentColor      = if (lightTheme) Color(0xFFB26A00) else Color(0xFFFFCC00)  // amber
    val deliveredColor = if (lightTheme) Color(0xFF2E7D32) else Color(0xFF4CAF50)  // green
    // The status is otherwise color-only — the contentDescription is the sole signal
    // a screen-reader user gets that a message failed.
    val (icon, tint, description) = when (status) {
        DELIVERY_STATUS_PENDING   -> Triple(Icons.Default.Schedule, MaterialTheme.colorScheme.onSurfaceVariant, "Sending")
        DELIVERY_STATUS_SENT      -> Triple(Icons.Default.Done, sentColor, "Sent")
        DELIVERY_STATUS_DELIVERED -> Triple(Icons.Default.DoneAll, deliveredColor, "Delivered")
        DELIVERY_STATUS_FAILED    -> Triple(Icons.Default.Error, MaterialTheme.colorScheme.error, "Failed to send. Tap to retry.")
        else -> return
    }
    if (status == DELIVERY_STATUS_FAILED && onRetry != null) {
        /* Failed is the only tappable state — minimumInteractiveComponentSize() reserves
         * the 48dp accessibility touch target around the 12dp glyph, instead of asking
         * users to hit a 12dp icon to recover a failed message. */
        Box(
            modifier = modifier
                .minimumInteractiveComponentSize()
                .clickable(onClick = onRetry),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = description,
                modifier = Modifier.size(12.dp), tint = tint)
        }
    } else {
        Icon(imageVector = icon, contentDescription = description,
            modifier = modifier.size(12.dp), tint = tint)
    }
}

// ── ReplyBar ───────────────────────────────────────────────────────────────────
// Bottom compose bar with text input, pending-attachment preview row,
// attach button (photo/video multi-picker + audio picker), and send button.

/** Maximum media items selectable per message. Carrier PDU caps (~300 KB – 1 MB)
 *  mean far fewer full-quality photos actually fit — the aggregate size budget in
 *  MmsManagerWrapper compresses each image to its share of the cap, so five is
 *  already ambitious on stingy carriers. */
private const val MAX_MMS_ATTACHMENTS = ThreadViewModel.MAX_ATTACHMENTS

@Composable
private fun ReplyBar(
    text: String,
    pendingAttachments: List<MessageAttachment>,
    // Non-null when the user has swiped to quote a message; drives the quote strip.
    replyingTo: Message? = null,
    // True when the thread has more than one MMS participant. Sending a group MMS
    // isn't implemented yet (MMS_AUDIT #6) — see the warning row rendered below.
    isGroupThread: Boolean = false,
    onTextChange: (String) -> Unit,
    onAttachmentsSelected: (List<MessageAttachment>) -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onClearReplyingTo: () -> Unit = {},
    onSend: () -> Unit,
    // Voice memo recording phase + event sink for the mic button (see VoiceMemoLogic).
    voiceMemo: ThreadViewModel.VoiceMemoUiState = ThreadViewModel.VoiceMemoUiState(),
    onVoiceMemoEvent: (VoiceMemoEvent) -> Unit = {},
    // Live 0..1 mic input level while recording; feeds the panel's level meter.
    recordingLevel: StateFlow<Float> = MutableStateFlow(0f),
    // Shared audio player — lets a pending memo be reviewed (played/scrubbed)
    // before sending.
    audioPlayback: StateFlow<AudioPlaybackState> = MutableStateFlow(AudioPlaybackState()),
    // uri → display waveform for recorded memos. Collected ONCE below (it changes
    // rarely) and resolved to a List<Float>? per chip.
    memoWaveforms: StateFlow<Map<String, List<Float>>> = MutableStateFlow(emptyMap()),
    onAudioPlayPause: (String) -> Unit = {},
    onAudioSeek: (String, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAttachMenu by remember { mutableStateOf(false) }
    // Collected once here (not per chip) — the map changes only when a take is
    // stored/removed, so a single subscription feeds every chip its resolved value.
    val waveforms by memoWaveforms.collectAsState()

    /* Android Photo Picker (multi-select, images + video). Jetpack-backed with a
     * Play Services shim below Android 13, so it works down to minSdk 26. Unlike the
     * old GetContent flow it shows a proper selection surface instead of resolving
     * straight to whichever app happens to be the default gallery. */
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_MMS_ATTACHMENTS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAttachmentsSelected(uris.map { uri ->
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                MessageAttachment(uri.toString(), mimeType)
            })
        }
    }

    // Audio files aren't handled by the Photo Picker — keep the GetContent flow.
    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            onAttachmentsSelected(listOf(MessageAttachment(uri.toString(), mimeType)))
        }
    }

    // RECORD_AUDIO gate for the attach-menu "Record voice memo" item (the mic button
    // has its own). On grant do nothing — the user re-taps the menu item, the same
    // convention the mic button uses; denial routes through the shared Settings helper.
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) onMicPermissionDenied(context)
    }

    // Only show the SMS counter when no attachment is pending (pure SMS mode).
    val counterText = if (pendingAttachments.isEmpty()) {
        remember(text.length) { smsCounter(text.length) }
    } else null

    val isRecording = voiceMemo.phase != VoiceMemoPhase.IDLE
    /* Live IME height (reading it in composition subscribes to every frame of the
     * open/close animation) and its value frozen at the moment recording began —
     * together they drive the keyboard-space filler panel below the input row.
     * Retained (non-snapshot) because it's written during composition. */
    val density = LocalDensity.current
    val imeVisiblePx = WindowInsets.ime.getBottom(density)
    val imeAtRecordStart = remember { Retained(0) }
    if (!isRecording) imeAtRecordStart.value = imeVisiblePx

    Column(modifier = modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // ── Group reply notice ───────────────────────────────────────────
                // Group MMS sending isn't implemented — a reply here would silently
                // go to only one participant, not the whole group. Warn instead.
                AnimatedVisibility(
                    visible = isGroupThread,
                    enter   = expandVertically() + fadeIn(),
                    exit    = shrinkVertically() + fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Group replies aren't supported yet — this will only reply to one participant.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                // ── Quote strip ──────────────────────────────────────────────────
                // Shown when the user has swiped to reply to a specific message.
                // The last non-null message is retained so the strip still has
                // content to render while it shrinks out after replyingTo clears
                // (AnimatedVisibility keeps composing its content until the exit
                // animation finishes).
                val lastQuoted = remember { Retained(replyingTo) }
                if (replyingTo != null) lastQuoted.value = replyingTo
                AnimatedVisibility(
                    visible = replyingTo != null,
                    enter   = expandVertically() + fadeIn(),
                    exit    = shrinkVertically() + fadeOut()
                ) {
                    // Live value while visible; the retained copy only feeds the
                    // exit frames (and the replyingTo read keeps this lambda
                    // recomposing when the quoted message changes in place).
                    val quoted = replyingTo ?: lastQuoted.value ?: return@AnimatedVisibility
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Colored left accent bar (same hue as the bubble).
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(36.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (quoted.isSent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary
                                )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text  = if (quoted.isSent) "You" else "Them",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (quoted.isSent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.tertiary,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            Text(
                                text     = quoted.body.ifBlank {
                                    when {
                                        quoted.mimeType?.startsWith("image/") == true -> "🖼️ Photo"
                                        quoted.mimeType?.startsWith("video/") == true -> "🎬 Video"
                                        quoted.mimeType?.startsWith("audio/") == true -> "🎵 Audio"
                                        else -> "📎 Attachment"
                                    }
                                },
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // Clear button removes the quote context without cancelling the reply.
                        IconButton(
                            onClick  = onClearReplyingTo,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Close,
                                contentDescription = "Cancel reply",
                                tint               = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier           = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                // Pending-attachment previews. Audio (usually a just-recorded voice
                // memo) renders as a full-width play/seek chip — an 80 dp tile with a
                // lone play button read as broken (found on-device July 17). Images
                // and videos keep the 80 dp thumbnail LazyRow. Last non-empty list
                // retained for the shrink-out, like the quote strip above.
                val lastAttachments = remember { Retained(pendingAttachments) }
                if (pendingAttachments.isNotEmpty()) lastAttachments.value = pendingAttachments
                AnimatedVisibility(
                    visible = pendingAttachments.isNotEmpty(),
                    enter   = expandVertically() + fadeIn(),
                    exit    = shrinkVertically() + fadeOut()
                ) {
                    val shown =
                        if (pendingAttachments.isNotEmpty()) pendingAttachments
                        else lastAttachments.value
                    Column(
                        modifier = Modifier.padding(bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Indices stay those of the full pending list — that's what
                        // onRemoveAttachment expects. During exit the strip renders
                        // the retained stale list; each × only forwards while the
                        // tapped item still matches the live list, so it can never
                        // remove a different attachment.
                        shown.forEachIndexed { index, attachment ->
                            if (attachment.mimeType.startsWith("audio/")) {
                                PendingAudioAttachment(
                                    attachment = attachment,
                                    audioPlayback = audioPlayback,
                                    // Resolved per-attachment from the CURRENT uri, so a
                                    // recycled position (an earlier attachment removed)
                                    // can never show a stale neighbour's waveform.
                                    waveform = waveforms[attachment.uri],
                                    onAudioPlayPause = onAudioPlayPause,
                                    onAudioSeek = onAudioSeek,
                                    onRemove = {
                                        if (pendingAttachments.getOrNull(index)?.uri == attachment.uri) {
                                            onRemoveAttachment(index)
                                        }
                                    }
                                )
                            }
                        }
                        val visual = shown.withIndex()
                            .filter { !it.value.mimeType.startsWith("audio/") }
                        if (visual.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                lazyRowItemsIndexed(visual) { _, indexed ->
                                    val (index, attachment) = indexed
                                    PendingAttachmentPreview(
                                        attachment = attachment,
                                        onRemove   = {
                                            if (pendingAttachments.getOrNull(index)?.uri == attachment.uri) {
                                                onRemoveAttachment(index)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Input row. Idle: [attach] [field] [send-or-mic]. Recording (held):
                // attach + field give way to the timer/hint row, but the mic button
                // keeps its call site so the node under the user's finger — and the
                // hold gesture running on it — survives the swap. Locked: the finger
                // is already up, so the row can freely become [timer] [cancel] [stop].
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = if (isRecording) Alignment.CenterVertically else Alignment.Bottom
                ) {
                    if (voiceMemo.phase == VoiceMemoPhase.LOCKED) {
                        // Stop / Restart / Cancel live in the voice panel below (big
                        // targets in the keyboard's space); the row keeps the timer.
                        RecordingStatusRow(
                            voiceMemo = voiceMemo,
                            locked = true,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        )
                    } else if (voiceMemo.phase == VoiceMemoPhase.PREVIEW) {
                        // The take itself (chip + Discard/Restart/Attach) is in the
                        // panel below; the row just says why the composer is parked.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text  = "Voice memo ready",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        if (!isRecording) {
                            // Attach button opens a dropdown with media type choices.
                            Box {
                                IconButton(onClick = { showAttachMenu = true }) {
                                    Icon(Icons.Default.AttachFile, contentDescription = "Attach media")
                                }
                                DropdownMenu(
                                    expanded = showAttachMenu,
                                    onDismissRequest = { showAttachMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text    = { Text("Photos or videos") },
                                        onClick = {
                                            showAttachMenu = false
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(
                                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                                )
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text    = { Text("Audio file") },
                                        onClick = {
                                            showAttachMenu = false
                                            audioLauncher.launch("audio/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text    = { Text("Record voice memo") },
                                        onClick = {
                                            showAttachMenu = false
                                            // Tap-to-record straight into hands-free LOCKED
                                            // via the same PRESS + LATCH_LOCK pair the
                                            // TalkBack path uses. PRESS's attachment-cap
                                            // guard and the table's no-ops make it safe from
                                            // any state; the menu is only reachable while IDLE.
                                            if (ContextCompat.checkSelfPermission(
                                                    context, Manifest.permission.RECORD_AUDIO
                                                ) != PackageManager.PERMISSION_GRANTED
                                            ) {
                                                recordAudioPermissionLauncher.launch(
                                                    Manifest.permission.RECORD_AUDIO
                                                )
                                            } else {
                                                onVoiceMemoEvent(VoiceMemoEvent.PRESS)
                                                onVoiceMemoEvent(VoiceMemoEvent.LATCH_LOCK)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            if (isRecording) {
                                RecordingStatusRow(
                                    voiceMemo = voiceMemo,
                                    locked = false,
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            } else {
                                TextField(
                                    value         = text,
                                    onValueChange = onTextChange,
                                    modifier      = Modifier.fillMaxWidth(),
                                    placeholder   = { Text("Message") },
                                    maxLines      = 4,
                                    colors        = TextFieldDefaults.colors(
                                        focusedContainerColor   = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        focusedIndicatorColor   = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor  = Color.Transparent,
                                    ),
                                    shape         = RoundedCornerShape(24.dp),
                                    textStyle     = MaterialTheme.typography.bodyMedium,
                                    trailingIcon  = counterText?.let { ct ->
                                        {
                                            Text(
                                                text     = ct,
                                                style    = MaterialTheme.typography.labelSmall,
                                                color    = if (text.length > 160) MaterialTheme.colorScheme.error
                                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        // WhatsApp/Google Messages pattern: the action button is a mic
                        // while the composer is empty and becomes send once there is
                        // anything to send. Typing and attaching are impossible while
                        // holding, so text/pendingAttachments can't change mid-hold from
                        // user input — but CAP_REACHED CAN fire mid-hold (MediaRecorder's
                        // max-duration callback while HELD auto-attaches the memo), which
                        // swaps mic -> send right under the still-held finger. That's
                        // still safe: the freshly composed send button never received the
                        // pointer-down, so lifting the finger doesn't click it — but don't
                        // assume this branch is truly static when touching it.
                        if (text.isBlank() && pendingAttachments.isEmpty()) {
                            VoiceMemoMicButton(
                                isRecording = isRecording,
                                onEvent = onVoiceMemoEvent
                            )
                        } else {
                            IconButton(
                                onClick  = onSend,
                                // Enabled when there is text OR media attachments are pending.
                                enabled  = text.isNotBlank() || pendingAttachments.isNotEmpty(),
                                colors   = IconButtonDefaults.iconButtonColors(
                                    containerColor         = MaterialTheme.colorScheme.primary,
                                    contentColor           = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    disabledContentColor   = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }
                    }
                }

                // ── Voice memo panel (in the keyboard's space) ────────────────────
                // Starting a recording removes the focused TextField, which closes
                // the IME; the Scaffold's imePadding would then slide this whole bar
                // down mid-gesture — disorienting, and worse, the mic moving under a
                // stationary finger reads as an upward relative drag (a spurious
                // lock latch). The panel's minimum height therefore grows in exact
                // counter-phase to the IME collapse (height captured at record start
                // − live height), so the input row never moves. While HELD that
                // stabilizer role is all it has (the finger is mid-gesture — no
                // buttons to reach, and with no keyboard open it must stay absent or
                // the bar would grow under the finger). Once hands-free (LOCKED /
                // PREVIEW) it always shows, as the recording workspace: big
                // stop/restart/cancel controls, then play/scrub + attach.
                val lastFillerPx = remember { Retained(0) }
                val fillerPx =
                    if (isRecording) {
                        (imeAtRecordStart.value - imeVisiblePx).coerceAtLeast(0)
                            .also { lastFillerPx.value = it }
                    } else lastFillerPx.value
                val panelVisible = when (voiceMemo.phase) {
                    VoiceMemoPhase.IDLE   -> false
                    VoiceMemoPhase.HELD   -> imeAtRecordStart.value > 0
                    VoiceMemoPhase.LOCKED,
                    VoiceMemoPhase.PREVIEW -> true
                }
                AnimatedVisibility(
                    visible = panelVisible,
                    // Entry height is already animated by the IME hand-off (or is
                    // content-sized) — an enter transition would fight it.
                    enter   = EnterTransition.None,
                    exit    = shrinkVertically() + fadeOut()
                ) {
                    VoiceMemoPanel(
                        voiceMemo        = voiceMemo,
                        minHeight        = with(density) { fillerPx.toDp() },
                        recordingLevel   = recordingLevel,
                        audioPlayback    = audioPlayback,
                        previewWaveform  = voiceMemo.previewUri?.let { waveforms[it] },
                        onAudioPlayPause = onAudioPlayPause,
                        onAudioSeek      = onAudioSeek,
                        onEvent          = onVoiceMemoEvent
                    )
                }
            }
        }
    }
}

// ── Voice memo recording UI ────────────────────────────────────────────────────

/* Mic-button gesture thresholds — dp so they scale with density, converted at the
 * gesture site (never raw pixels). Lock is a deliberate upward slide; cancel is a
 * longer leftward slide so it can't fire from drift while talking. */
private val VOICE_LOCK_DRAG_THRESHOLD   = 72.dp
private val VOICE_CANCEL_DRAG_THRESHOLD = 96.dp

/* Number of amplitude samples the level meter keeps on screen at once — a count, not a
 * pixel dimension; the bar geometry is derived from the canvas size and this. */
private const val VOICE_METER_BAR_COUNT = 30

/** A short confirmation buzz for a successful capture (quick-flow keep, preview attach).
 *  CONFIRM reads as "done" on API 30+; older devices fall back to the tick we already
 *  use for the lock latch. */
private fun View.performConfirmHaptic() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.CONTEXT_CLICK
    )
}

/** Denial handling shared by every RECORD_AUDIO request path. A permanent denial
 *  (rationale flag false after a completed request = "don't ask again" or policy)
 *  can only be undone in Settings, so deep-link there; a plain denial keeps the
 *  explanatory toast. */
private fun onMicPermissionDenied(context: Context) {
    // Walk the ContextWrapper chain to the hosting Activity (needed for the rationale
    // check); null if this context isn't Activity-backed.
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()
    if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
            activity, Manifest.permission.RECORD_AUDIO
        )
    ) {
        Toast.makeText(
            context,
            "Microphone permission is off — enable it in Settings for voice memos",
            Toast.LENGTH_LONG
        ).show()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            )
        }
    } else {
        Toast.makeText(
            context,
            "Microphone permission is needed to record voice memos",
            Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * Live input-level meter: a right-anchored history of the most recent
 * [VOICE_METER_BAR_COUNT] mic-amplitude samples as evenly spaced rounded bars, newest
 * on the right. Proves the mic is actually capturing — a dead mic renders as a flatline
 * instead of being discovered only after sending.
 *
 * Reads [level] LOCALLY so its ~15 Hz ticks recompose only this Canvas, never the
 * reply bar around it (the same isolation the AudioChip uses for its playback position).
 */
@Composable
private fun RecordingLevelMeter(
    level: StateFlow<Float>,
    modifier: Modifier = Modifier
) {
    // Ring buffer of recent samples, appended on the meter's own clock rather than on
    // flow emissions: StateFlow conflates equal values, so a silent stretch (0f, 0f, …)
    // emits nothing — collect-driven appends would freeze the scroll mid-pattern with
    // stale loud bars on screen. Sampling level.value at the tick rate keeps the
    // history scrolling, so silence visibly flattens out.
    val samples = remember { mutableStateListOf<Float>() }
    LaunchedEffect(level) {
        while (true) {
            samples.add(level.value)
            while (samples.size > VOICE_METER_BAR_COUNT) samples.removeAt(0)
            delay(66) // matches the ViewModel ticker's ~15 Hz
        }
    }
    // Read the theme color OUTSIDE the draw lambda — MaterialTheme can't be read inside
    // Canvas's DrawScope.
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .widthIn(max = 240.dp)
            .fillMaxWidth()
            .height(48.dp)
    ) {
        val slotWidth = size.width / VOICE_METER_BAR_COUNT
        val barWidth  = slotWidth / 2f
        val minBarHeight = 4.dp.toPx()
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        samples.forEachIndexed { index, sample ->
            // Newest sample sits in the rightmost slot; a partly-filled buffer hugs the
            // right edge so the history scrolls in from the right as it grows.
            val slot = VOICE_METER_BAR_COUNT - samples.size + index
            val barHeight = maxOf(minBarHeight, sample * size.height)
            drawRoundRect(
                color        = barColor,
                topLeft      = Offset(slot * slotWidth + (slotWidth - barWidth) / 2f, (size.height - barHeight) / 2f),
                size         = Size(barWidth, barHeight),
                cornerRadius = radius
            )
        }
    }
}

/**
 * The mic button and its entire capture gesture: hold to record, slide up to latch
 * hands-free ([VoiceMemoEvent.LATCH_LOCK], CONTEXT_CLICK haptic), slide left to
 * cancel, release to stop-and-keep. The pointerInput lives on this button ONLY —
 * never on or over message bubbles (child detectors there break the bubbles'
 * combinedClickable; see CHANGELOG 2026-07-12).
 *
 * On first press without RECORD_AUDIO, launches the system permission prompt
 * instead of recording; a denial gets a toast so the button never fails silently.
 */
@Composable
private fun VoiceMemoMicButton(
    isRecording: Boolean,
    onEvent: (VoiceMemoEvent) -> Unit
) {
    val context = LocalContext.current
    val view    = LocalView.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val lockThresholdPx   = with(density) { VOICE_LOCK_DRAG_THRESHOLD.toPx() }
    val cancelThresholdPx = with(density) { VOICE_CANCEL_DRAG_THRESHOLD.toPx() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) onMicPermissionDenied(context)
    }

    // Shared by the touch gesture below and the TalkBack semantics path, so both
    // agree on whether a press should record or ask first.
    val hasMicPermission = {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (isRecording) MaterialTheme.colorScheme.primary else Color.Transparent
            )
            .semantics(mergeDescendants = true) {
                // The hold/slide gesture below is invisible to accessibility services
                // (pointerInput has no semantics of its own) — TalkBack's double-tap
                // fires this onClick instead, never the pointerInput block below (its
                // onClick only ever fires for accessibility actions, not physical
                // touches, so the too-short-press toast path there is unaffected). One
                // tap always starts a hands-free LOCKED recording; the panel's
                // Stop/Cancel/Restart/Attach controls are ordinary buttons and already
                // accessible. Safety is free here: if PRESS's phase guard fails (already
                // recording) the phase just stays put, and LATCH_LOCK is a table no-op
                // on every phase but HELD — this can never desync the state machine.
                onClick(label = "start recording") {
                    if (!hasMicPermission()) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        onEvent(VoiceMemoEvent.PRESS)
                        onEvent(VoiceMemoEvent.LATCH_LOCK)
                    }
                    true
                }
                // mergeDescendants above folds the Icon's own contentDescription
                // ("Record voice memo") into this node — no second description needed.
                if (isRecording) stateDescription = "Recording"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!hasMicPermission()) {
                        // First mic press: ask, don't record. The user holds again
                        // after granting.
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    down.consume()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEvent(VoiceMemoEvent.PRESS)
                    var dragTotal = Offset.Zero
                    var latched = false
                    while (true) {
                        val event  = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // Lifting after the latch is the point of locking — ignore it.
                            // A quick-flow keep gets a confirmation buzz (the latch path
                            // already buzzed on CONTEXT_CLICK; cancel/discard stay silent).
                            if (!latched) {
                                view.performConfirmHaptic()
                                onEvent(VoiceMemoEvent.RELEASE)
                            }
                            break
                        }
                        dragTotal += change.positionChange()
                        change.consume()
                        if (!latched && shouldCancelDrag(dragTotal.x, cancelThresholdPx)) {
                            onEvent(VoiceMemoEvent.CANCEL)
                            break
                        }
                        if (!latched && shouldLatchLock(dragTotal.y, lockThresholdPx)) {
                            latched = true
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            onEvent(VoiceMemoEvent.LATCH_LOCK)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector        = Icons.Default.Mic,
            contentDescription = "Record voice memo",
            tint               = if (isRecording) MaterialTheme.colorScheme.onPrimary
                                 else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 10 Hz elapsed-time ticker for the recording UI — display-only; the hard duration
 *  cap is enforced by MediaRecorder itself. [startedAtMs] is an elapsedRealtime()
 *  timestamp (see VoiceMemoUiState.startedAtMs), so this stays correct across an
 *  NTP step mid-recording. */
@Composable
private fun rememberRecordingElapsedMs(startedAtMs: Long): Long {
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            delay(100)
        }
    }
    return elapsedMs
}

/**
 * The voice memo workspace that fills the space the keyboard vacates (and shows on
 * its own for hands-free phases even when no keyboard was open). Content by phase:
 * HELD — decorative pulsing mic (the finger is mid-gesture; hints are in the input
 * row). LOCKED — big timer + Cancel / Stop / Restart. PREVIEW — the take as a
 * play/scrub chip + Discard / Restart / Attach; attaching hands it to the pending
 * strip like any other attachment. [minHeight] is the keyboard-compensation height
 * (0 when the keyboard was closed); content may grow past it.
 */
@Composable
private fun VoiceMemoPanel(
    voiceMemo: ThreadViewModel.VoiceMemoUiState,
    minHeight: Dp,
    recordingLevel: StateFlow<Float>,
    audioPlayback: StateFlow<AudioPlaybackState>,
    // Display waveform for the parked preview take, or null (→ Slider fallback).
    previewWaveform: List<Float>?,
    onAudioPlayPause: (String) -> Unit,
    onAudioSeek: (String, Float) -> Unit,
    onEvent: (VoiceMemoEvent) -> Unit
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        when (voiceMemo.phase) {
            VoiceMemoPhase.HELD -> {
                // The live meter replaces the old decorative pulsing mic — it pulses with
                // real input, so a dead mic reads as a flatline instead of a false "alive"
                // animation the user only discovers is empty after sending.
                RecordingLevelMeter(level = recordingLevel)
            }
            VoiceMemoPhase.LOCKED -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text  = formatMemoDuration(rememberRecordingElapsedMs(voiceMemo.startedAtMs)) +
                                " / " + formatMemoDuration(voiceMemo.maxDurationMs),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    RecordingLevelMeter(level = recordingLevel)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TextButton(onClick = { onEvent(VoiceMemoEvent.CANCEL) }) {
                            Text("Cancel")
                        }
                        FilledIconButton(
                            onClick  = { onEvent(VoiceMemoEvent.STOP_TAP) },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Stop,
                                contentDescription = "Stop recording",
                                modifier           = Modifier.size(32.dp)
                            )
                        }
                        TextButton(onClick = { onEvent(VoiceMemoEvent.RESTART) }) {
                            Text("Restart")
                        }
                    }
                }
            }
            VoiceMemoPhase.PREVIEW -> {
                val uri = voiceMemo.previewUri
                if (uri != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AudioChip(
                            uri = uri,
                            audioPlayback = audioPlayback,
                            onPlayPause = onAudioPlayPause,
                            onSeek = onAudioSeek,
                            fallbackDurationMs = rememberAudioDurationMs(uri),
                            waveform = previewWaveform
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TextButton(onClick = { onEvent(VoiceMemoEvent.CANCEL) }) {
                                Text("Discard")
                            }
                            OutlinedButton(onClick = { onEvent(VoiceMemoEvent.RESTART) }) {
                                Text("Restart")
                            }
                            Button(onClick = {
                                view.performConfirmHaptic()
                                onEvent(VoiceMemoEvent.ATTACH)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Attach")
                            }
                        }
                    }
                }
            }
            VoiceMemoPhase.IDLE -> { /* composed only during the exit animation */ }
        }
    }
}

/**
 * The in-progress recording indicator that replaces the text field: pulsing red dot,
 * elapsed / cap timer, and (while still held) the lock & cancel gesture hints.
 */
@Composable
private fun RecordingStatusRow(
    voiceMemo: ThreadViewModel.VoiceMemoUiState,
    locked: Boolean,
    modifier: Modifier = Modifier
) {
    val elapsedMs = rememberRecordingElapsedMs(voiceMemo.startedAtMs)
    val pulse by rememberInfiniteTransition(label = "recPulse").animateFloat(
        initialValue  = 1f,
        targetValue   = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label         = "recPulseAlpha"
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error.copy(alpha = pulse))
        )
        Text(
            text  = "${formatMemoDuration(elapsedMs)} / ${formatMemoDuration(voiceMemo.maxDurationMs)}",
            style = MaterialTheme.typography.bodyMedium
        )
        if (!locked) {
            Text(
                text     = "Slide ↑ to lock · ← to cancel",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/* Successful uri → duration-ms reads, so a chip labels correctly on its first frame
 * (no IO) once any prior chip has read the same uri. Failures are never cached — a file
 * still downloading may succeed later. Bounded so a long-lived process can't grow it. */
private val audioDurationCache = android.util.LruCache<String, Long>(256)

/** Duration of the audio at [uri] in ms; null while loading or on failure. Backs the
 *  pending-memo preview tile and the bubble chips, which have no player state until the
 *  user taps play. Each uri costs exactly one metadata read, ever — the result is cached,
 *  so a chip scrolling back into view (or another chip on the same uri) is free. */
@Composable
private fun rememberAudioDurationMs(uri: String): Long? {
    val ctx = LocalContext.current
    // Cache hit seeds the initial value → correct label on the first frame with no IO.
    val duration by produceState<Long?>(audioDurationCache.get(uri), uri) {
        // A uri change restarts only this producer — the state keeps the PREVIOUS uri's
        // value. Re-seed from the cache (null on miss) before deciding to skip the read,
        // or a recycled call site would keep showing the old attachment's duration.
        value = audioDurationCache.get(uri)
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ctx, Uri.parse(uri))
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.also { audioDurationCache.put(uri, it) }
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
    return duration
}

// ── PendingAudioAttachment ─────────────────────────────────────────────────────

/**
 * Full-width review row for a pending audio attachment (usually a just-recorded
 * voice memo): the same play/seek chip the audio bubbles use, plus an × to discard.
 * Full-width rather than an 80 dp strip tile because reviewing a memo wants
 * scrubbing and a visible duration (found on-device July 17). The duration comes
 * from file metadata so it shows before the player ever loads the file.
 */
@Composable
private fun PendingAudioAttachment(
    attachment: MessageAttachment,
    audioPlayback: StateFlow<AudioPlaybackState>,
    // Display waveform for this recorded memo, or null (→ Slider fallback).
    waveform: List<Float>?,
    onAudioPlayPause: (String) -> Unit,
    onAudioSeek: (String, Float) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            AudioChip(
                uri = attachment.uri,
                audioPlayback = audioPlayback,
                onPlayPause = onAudioPlayPause,
                onSeek = onAudioSeek,
                fallbackDurationMs = rememberAudioDurationMs(attachment.uri),
                waveform = waveform
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove attachment",
                modifier = Modifier.size(18.dp),
                tint     = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── PendingAttachmentPreview ───────────────────────────────────────────────────

/**
 * One 80 dp preview tile in the reply bar's pending-attachment row: image thumbnail,
 * video first-frame (with play badge), or a labelled placeholder for other types.
 * Audio never reaches this tile — it renders as [PendingAudioAttachment], a
 * full-width playable chip. The × badge at the top-right removes just this attachment.
 */
@Composable
private fun PendingAttachmentPreview(
    attachment: MessageAttachment,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.size(80.dp)) {
        when {
            attachment.mimeType.startsWith("image/") -> {
                /* Real thumbnail so the user can confirm which photo is attached
                 * before hitting send. */
                SubcomposeAsyncImage(
                    model               = Uri.parse(attachment.uri),
                    contentDescription  = "Attachment preview",
                    contentScale        = ContentScale.Crop,
                    modifier            = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            attachment.mimeType.startsWith("video/") -> {
                /* Cached first frame (see rememberVideoThumbnail) so the user can see
                 * which video is attached; play-icon placeholder while loading. */
                val videoThumb = rememberVideoThumbnail(attachment.uri)
                if (videoThumb != null) {
                    Image(
                        bitmap             = videoThumb!!.asImageBitmap(),
                        contentDescription = "Video preview",
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector        = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier           = Modifier.size(32.dp),
                            tint               = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Semi-transparent play badge overlaid at centre to signal it's a video.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier           = Modifier.size(16.dp),
                        tint               = Color.White
                    )
                }
            }
            else -> {
                // Generic file — labelled placeholder tile.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = "📎 File",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // Close button overlaid at the top-right corner.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove attachment",
                modifier = Modifier.size(12.dp),
                tint     = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ── BackupPolicyDialog ─────────────────────────────────────────────────────────

@Composable
private fun BackupPolicyDialog(
    currentPolicy: BackupPolicy,
    onPolicySelected: (BackupPolicy) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(currentPolicy) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup settings") },
        text = {
            Column {
                BackupPolicy.entries.forEach { policy ->
                    val label = when (policy) {
                        BackupPolicy.GLOBAL -> "Global policy"
                        BackupPolicy.ALWAYS_INCLUDE -> "Always include"
                        BackupPolicy.NEVER_INCLUDE -> "Never include"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = policy }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == policy,
                            onClick = { selected = policy }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPolicySelected(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Helpers ────────────────────────────────────────────────────────────────────

// Thread-safe (immutable), unlike the SimpleDateFormat it replaces.
private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

private fun localDateToLabel(date: LocalDate): String = date.format(DAY_FORMATTER)

/**
 * Corner radii for message bubbles.
 * The "sender side" (right for sent, left for received) gets the small corner radius
 * wherever the bubble attaches to its cluster neighbour.
 *
 * All eight shapes are precomputed: bubbleShape() is called per visible bubble per
 * recomposition, and RoundedCornerShape allocation in that path is pure churn.
 */
private val bubbleFull  = 16.dp
private val bubbleSmall = 4.dp
private val sentShapes = mapOf(
    ClusterPosition.SINGLE to RoundedCornerShape(bubbleFull),
    ClusterPosition.TOP    to RoundedCornerShape(topStart = bubbleFull, topEnd = bubbleFull,  bottomEnd = bubbleSmall, bottomStart = bubbleFull),
    ClusterPosition.MIDDLE to RoundedCornerShape(topStart = bubbleFull, topEnd = bubbleSmall, bottomEnd = bubbleSmall, bottomStart = bubbleFull),
    ClusterPosition.BOTTOM to RoundedCornerShape(topStart = bubbleFull, topEnd = bubbleSmall, bottomEnd = bubbleFull,  bottomStart = bubbleFull)
)
private val receivedShapes = mapOf(
    ClusterPosition.SINGLE to RoundedCornerShape(bubbleFull),
    ClusterPosition.TOP    to RoundedCornerShape(topStart = bubbleFull,  topEnd = bubbleFull, bottomEnd = bubbleFull, bottomStart = bubbleSmall),
    ClusterPosition.MIDDLE to RoundedCornerShape(topStart = bubbleSmall, topEnd = bubbleFull, bottomEnd = bubbleFull, bottomStart = bubbleSmall),
    ClusterPosition.BOTTOM to RoundedCornerShape(topStart = bubbleSmall, topEnd = bubbleFull, bottomEnd = bubbleFull, bottomStart = bubbleFull)
)

private fun bubbleShape(isSent: Boolean, position: ClusterPosition): RoundedCornerShape =
    (if (isSent) sentShapes else receivedShapes).getValue(position)

/**
 * Calculates the top-Y offset (in pixels) for the emoji reaction pill.
 * Places the pill just below the bubble; clamps so it never goes off the bottom of the screen.
 *
 * [bubbleBottomY] is the Y coordinate of the bubble's bottom edge in root coordinates.
 * [maxPillTopPx]  is the largest allowed top-Y for the pill (screen height − pill height − padding);
 *                 the pill height is already folded into this bound by the caller.
 *
 * Extracted as a pure function so it can be unit-tested without Compose.
 */
internal fun reactionPillTopPx(
    bubbleBottomY: Float,
    gapPx: Float,
    maxPillTopPx: Float
): Float = minOf(bubbleBottomY + gapPx, maxPillTopPx)

private fun smsCounter(length: Int): String? {
    if (length <= 120) return null
    if (length <= 160) return "$length / 160"
    val charsPerPart = 153
    val parts = (length + charsPerPart - 1) / charsPerPart
    return "${(length - 1) / charsPerPart + 1}/$parts"
}

// ── ReactionPills ─────────────────────────────────────────────────────────────

// ── linkifyText ───────────────────────────────────────────────────────────────

/**
 * Scans [text] for URLs and phone numbers and returns an [androidx.compose.ui.text.AnnotatedString]
 * with coloured, underlined spans and string annotations so the caller can launch the right
 * [Intent] when the user taps.
 *
 * - URLs  → "URL"   annotation → [Intent.ACTION_VIEW]
 * - Phone → "PHONE" annotation → [Intent.ACTION_DIAL]
 *
 * Phone ranges that overlap an already-matched URL range are skipped to avoid
 * double-annotating telephone numbers embedded in URLs (e.g. `tel:` links).
 *
 * @param text      Raw message body.
 * @param linkColor Colour applied to detected links; pass `MaterialTheme.colorScheme.primary`.
 */
private fun linkifyText(
    text: String,
    linkColor: Color,
    context: Context,
): androidx.compose.ui.text.AnnotatedString {
    // Links are attached with addLink(LinkAnnotation, …) rather than a plain string
    // annotation + onClick. This is what lets the surrounding Text stay a normal Text:
    // link ranges claim only taps that land on them, so long-press and taps on the rest
    // of the bubble still reach the parent combinedClickable (selection / emoji popup).
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    )
    return buildAnnotatedString {
        append(text)

        // ── URLs ──────────────────────────────────────────────────────────────
        val urlMatcher = android.util.Patterns.WEB_URL.matcher(text)
        val urlRanges  = mutableListOf<IntRange>()
        while (urlMatcher.find()) {
            val start = urlMatcher.start()
            val end   = urlMatcher.end()
            urlRanges += start until end
            // WEB_URL matches schemeless hosts (e.g. "example.com"); ACTION_VIEW needs a scheme.
            val raw = urlMatcher.group()
            val url = if (raw.startsWith("http", ignoreCase = true)) raw else "http://$raw"
            addLink(LinkAnnotation.Url(url, linkStyles), start, end)
        }

        // ── Phone numbers ─────────────────────────────────────────────────────
        val phoneMatcher = android.util.Patterns.PHONE.matcher(text)
        while (phoneMatcher.find()) {
            val start = phoneMatcher.start()
            val end   = phoneMatcher.end()
            // Skip phone matches that overlap a URL match (e.g. number in a URL path).
            if (urlRanges.any { start < it.last && end > it.first }) continue
            val number = phoneMatcher.group()
            addLink(
                LinkAnnotation.Clickable("PHONE", linkStyles) {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                },
                start, end
            )
        }
    }
}

// ── FullScreenImageViewer ─────────────────────────────────────────────────────

/**
 * Full-screen overlay that displays one or more MMS images with pinch-to-zoom support,
 * modeled loosely on Google Messages' image viewer (not a pixel match — see the header/
 * action row below for what Postmark actually offers).
 *
 * The images are shown on a black scrim. The user can:
 *  - Swipe horizontally to page between images (when more than one) — [images] spans the
 *    whole thread, not just the tapped message's own attachments, so paging can cross
 *    message boundaries, matching the swipe-through-gallery behavior of other messaging apps.
 *    Adjacent pages peek in from the edges (contentPadding + pageSpacing on the pager).
 *  - Pinch to zoom (1× – 5×) and pan while zoomed
 *  - Download, delete (with confirmation — see [onDeleteRequest]), forward, share, star,
 *    or view details via the top bar / overflow menu
 *  - Tap a quick-reaction emoji at the bottom, same reactions as long-pressing a bubble
 *  - Tap "Go to chat" to jump straight to that image's message in the conversation
 *  - Tap the scrim or press Back to dismiss
 *
 * @param images              Every image in the thread, in chronological order.
 * @param initialIndex        Index of the image the user tapped, shown first.
 * @param contactDisplayName  Name shown for received images ("You" is used for sent ones).
 * @param quickReactionEmojis Same ranked quick-reaction set as the bubble long-press popup.
 * @param onToggleReaction    Same reaction toggle used by bubbles/`ReactionPills`.
 * @param onToggleStarred     Toggles the current page's starred state.
 * @param onJumpToMessage     Called with the current page's message ID when "Go to chat" is
 *                            tapped. The caller is expected to dismiss the viewer and scroll.
 * @param onDeleteRequest     Called with the current page's message ID when the trash icon
 *                            is tapped. The caller is expected to dismiss the viewer and show
 *                            a confirmation dialog before actually deleting — this composable
 *                            never deletes anything itself.
 * @param onForward           Called with the current page's message ID when "Forward" is
 *                            tapped. The caller is expected to dismiss the viewer and navigate.
 * @param onDismiss           Called when the user closes the viewer.
 */
@Composable
private fun FullScreenImageViewer(
    images: List<ThreadImageRef>,
    initialIndex: Int,
    contactDisplayName: String,
    // Real nav-bar height read from the Activity window (see call site) — applied as an
    // explicit padding value because navigationBarsPadding() computed inside this
    // composable's own Dialog proved unreliable on-device (kept reading zero on a real
    // Samsung phone even after forcing decorFitsSystemWindows = false on the dialog's
    // own Window).
    navBarBottomPadding: androidx.compose.ui.unit.Dp,
    quickReactionEmojis: List<String>,
    onToggleReaction: (Long, String) -> Unit,
    onToggleStarred: (Long) -> Unit,
    onJumpToMessage: (Long) -> Unit,
    onDeleteRequest: (Long) -> Unit,
    onForward: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
    ) { images.size }
    val currentImage = images.getOrNull(pagerState.currentPage)
    val context = LocalContext.current
    // Allow the device to rotate while viewing full-screen photos (e.g. landscape shots);
    // reverts to the app-wide portrait lock on close.
    AllowScreenRotationWhileVisible()
    val coroutineScope = rememberCoroutineScope()
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDetailsFor by remember { mutableStateOf<ThreadImageRef?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    fun runDownload(uri: String) {
        coroutineScope.launch {
            val saved = downloadImageToGallery(context, uri)
            Toast.makeText(
                context,
                if (saved) "Saved to Pictures" else "Couldn't save image",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    // API 26-28 only: MediaStore inserts on those versions still need the runtime
    // WRITE_EXTERNAL_STORAGE permission (API 29+ scoped storage needs none for the
    // app's own inserted content). uriPendingPermission holds the tapped image's URI
    // across the request so the callback below knows what to download once granted.
    var uriPendingPermission by remember { mutableStateOf<String?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val uri = uriPendingPermission
        uriPendingPermission = null
        if (granted && uri != null) {
            runDownload(uri)
        } else if (!granted) {
            Toast.makeText(context, "Storage permission needed to save images", Toast.LENGTH_SHORT).show()
        }
    }
    fun downloadWithPermissionCheck(uri: String) {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            uriPendingPermission = uri
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            runDownload(uri)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Without this, the dialog window is constrained to the platform's default
        // (non-fullscreen) size — Modifier.fillMaxSize() below only fills *that* smaller
        // window, leaving the real screen edges (status bar, ThreadScreen's own top bar
        // and bottom bubbles) visible around a smaller black box instead of covering them.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // usePlatformDefaultWidth = false alone makes the dialog's CONTENT fill the
        // screen, but the dialog's underlying Window still defaults to
        // decorFitsSystemWindows = true — meaning this Window never actually receives
        // real navigationBars/statusBars WindowInsets values, so navigationBarsPadding()/
        // statusBarsPadding() below silently computed zero padding and the bottom row sat
        // behind the 3-button nav bar / gesture handle instead of above it. Reaching into
        // the dialog's own Window (not the Activity's) and flipping this flag is required
        // for THIS window's insets to be reported at all.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Tap outside the image (on the scrim) dismisses the viewer.
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            // contentPadding + pageSpacing leaves the previous/next image's edge visible
            // during a swipe instead of each page filling the entire viewer edge-to-edge.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 28.dp),
                pageSpacing = 10.dp
            ) { page ->
                ZoomableImage(
                    uri = images[page].uri,
                    // Depth cue on the peeking pages: slightly smaller and dimmer,
                    // easing to full size/opacity as they settle. Lambda graphicsLayer
                    // keeps the per-scroll-frame offset read in the draw phase — the
                    // parameter overload would recompose every page on every scrolled
                    // frame (same principle as the pinch-zoom layer inside).
                    modifier = Modifier.graphicsLayer {
                        val distance = pagerState.getOffsetDistanceInPages(page)
                            .absoluteValue.coerceIn(0f, 1f)
                        val scale = 1f - 0.08f * distance
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.4f * distance
                    }
                )
            }

            // Top bar: close, sender + friendly timestamp, download/delete/overflow.
            currentImage?.let { image ->
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (image.isSent) "You" else contactDisplayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = image.timestampLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    IconButton(onClick = { downloadWithPermissionCheck(image.uri) }) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
                    }
                    IconButton(onClick = { onDeleteRequest(image.messageId) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Forward") },
                                onClick = { showOverflowMenu = false; onForward(image.messageId) }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = { showOverflowMenu = false; shareImage(context, image.uri) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (image.isStarred) "Unstar" else "Star") },
                                onClick = { showOverflowMenu = false; onToggleStarred(image.messageId) }
                            )
                            DropdownMenuItem(
                                text = { Text("View details") },
                                onClick = { showOverflowMenu = false; showDetailsFor = image }
                            )
                        }
                    }
                }
            }

            // Bottom: page counter + "Go to chat" (so closing the viewer doesn't strand
            // you wherever you happened to be scrolled before opening it), then a row of
            // quick-reaction emojis — the same ranked set and toggle as long-pressing a bubble.
            // Extra bottom margin (28.dp beyond the real nav-bar height, not just 12.dp)
            // so the reaction row sits comfortably above the nav bar / edge of the
            // screen rather than hugging it.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarBottomPadding + 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (images.size > 1) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${images.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    currentImage?.let { image ->
                        Surface(
                            onClick = { onJumpToMessage(image.messageId) },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 3.dp,
                            shadowElevation = 2.dp
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Go to chat",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
                currentImage?.let { image ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.6f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            quickReactionEmojis.forEach { emoji ->
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .clickable { onToggleReaction(image.messageId, emoji) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = emoji, fontSize = 28.sp)
                                }
                            }
                            // "+" opens the same full emoji picker as long-pressing a bubble,
                            // not just the ~5 quick-pick reactions.
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable { showEmojiPicker = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "More emoji",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    showDetailsFor?.let { image ->
        ImageDetailsDialog(
            image = image,
            contactDisplayName = contactDisplayName,
            onDismiss = { showDetailsFor = null }
        )
    }

    if (showEmojiPicker) {
        EmojiPickerBottomSheet(
            onEmojiSelected = { emoji ->
                currentImage?.let { onToggleReaction(it.messageId, emoji) }
            },
            onDismiss = { showEmojiPicker = false }
        )
    }
}

/**
 * "View details" dialog for the image viewer's overflow menu. Shows the message-level
 * facts immediately (sender, timestamp, starred), then loads photo EXIF metadata
 * (date taken, camera, dimensions, GPS, file size) asynchronously — reading EXIF/file
 * size touches the content provider so it can't be free.
 *
 * EXIF availability varies a lot in practice: Postmark's own outgoing-image compression
 * (`MmsManagerWrapper.compressImage`) decodes via `BitmapFactory`, which does not
 * preserve EXIF, so **sent** images essentially never have metadata beyond what
 * Postmark already knows. Received images keep whatever the sender's phone/carrier
 * left intact, which is inconsistent (some carriers strip EXIF for size/privacy).
 * The dialog shows only the fields it actually finds, with a clear message when none
 * of them exist rather than a set of misleading blanks.
 */
@Composable
private fun ImageDetailsDialog(
    image: ThreadImageRef,
    contactDisplayName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var metadata by remember(image.uri) { mutableStateOf<ImageMetadata?>(null) }
    var loadingMetadata by remember(image.uri) { mutableStateOf(true) }
    LaunchedEffect(image.uri) {
        metadata = readImageMetadata(context, image.uri)
        loadingMetadata = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Image details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (image.isSent) "Sent by you" else "Received from $contactDisplayName")
                Text(image.timestampLabel)
                if (image.isStarred) Text("★ Starred")

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (loadingMetadata) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    val md = metadata
                    val hasAnyMetadata = md != null && (
                        md.dateTaken != null || md.camera != null || md.dimensions != null ||
                        md.fileSizeLabel != null || (md.latitude != null && md.longitude != null)
                    )
                    if (md != null && hasAnyMetadata) {
                        md.dateTaken?.let { Text("Taken: $it") }
                        md.camera?.let { Text("Camera: $it") }
                        md.dimensions?.let { Text("Dimensions: $it") }
                        md.fileSizeLabel?.let { Text("Size: $it") }
                        if (md.latitude != null && md.longitude != null) {
                            val lat = md.latitude
                            val lon = md.longitude
                            Text(
                                text = "Location: %.4f, %.4f (tap to open in Maps)".format(lat, lon),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    val geoUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                                    context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
                                }
                            )
                        }
                    } else {
                        Text(
                            text = "No photo metadata found — either stripped by compression " +
                                "(common for images you sent) or never present in the original file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

/**
 * Saves an image (content:// URI, any source app can read) into the device's public
 * Pictures/Postmark gallery folder. Returns false (never throws) on any failure so the
 * caller can show a plain "couldn't save" toast rather than crashing the viewer.
 *
 * API 29+: MediaStore insert with RELATIVE_PATH/IS_PENDING needs no permission for the
 * app's own inserted content (scoped storage). API 26-28: MediaStore insert still needs
 * the runtime WRITE_EXTERNAL_STORAGE permission — see the launcher wired up alongside the
 * download button's caller for that path.
 */
private suspend fun downloadImageToGallery(context: android.content.Context, uri: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val sourceUri = Uri.parse(uri)
            val mimeType = resolver.getType(sourceUri) ?: "image/jpeg"
            val extension = when {
                mimeType.contains("png") -> "png"
                mimeType.contains("gif") -> "gif"
                mimeType.contains("webp") -> "webp"
                else -> "jpg"
            }
            val displayName = "Postmark_${System.currentTimeMillis()}.$extension"
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Postmark")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val destUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            resolver.openOutputStream(destUri)?.use { out ->
                resolver.openInputStream(sourceUri)?.use { it.copyTo(out) }
                    ?: return@withContext false
            } ?: return@withContext false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(destUri, values, null, null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

/** Opens the system share sheet for one MMS image. content://mms/part/ URIs support
 *  Intent.FLAG_GRANT_READ_URI_PERMISSION grants (same mechanism the platform's own
 *  Messages app relies on to forward/share MMS attachments), so no FileProvider copy
 *  is needed — the receiving app reads the same content URI directly. */
private fun shareImage(context: android.content.Context, uri: String) {
    val parsed = Uri.parse(uri)
    val mimeType = context.contentResolver.getType(parsed) ?: "image/jpeg"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, parsed)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share image"))
}

/** EXIF/file facts for the "View details" dialog. Every field is independently
 *  optional — see [readImageMetadata] for why availability varies so much. */
private data class ImageMetadata(
    val dateTaken: String? = null,
    val camera: String? = null,
    val dimensions: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val fileSizeLabel: String? = null
)

private val EXIF_DATETIME_FORMAT =
    java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
private val FRIENDLY_DATETIME_FORMAT =
    java.text.SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())

/** Reads whatever EXIF metadata and file size are available for [uri]. Never throws —
 *  a corrupt/absent EXIF block or an unreadable stream just leaves those fields null,
 *  since "no metadata" is an expected, common outcome (see [ImageDetailsDialog]'s doc). */
private suspend fun readImageMetadata(context: android.content.Context, uri: String): ImageMetadata =
    withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        var dateTaken: String? = null
        var camera: String? = null
        var dimensions: String? = null
        var latitude: Double? = null
        var longitude: Double? = null

        try {
            context.contentResolver.openInputStream(parsed)?.use { input ->
                val exif = androidx.exifinterface.media.ExifInterface(input)
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?.let { raw ->
                        dateTaken = try {
                            EXIF_DATETIME_FORMAT.parse(raw)?.let { FRIENDLY_DATETIME_FORMAT.format(it) }
                        } catch (_: Exception) { null }
                    }
                val make = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE)?.trim()
                val model = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL)?.trim()
                camera = listOfNotNull(make, model).joinToString(" ").ifBlank { null }
                val w = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
                val h = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
                if (w > 0 && h > 0) dimensions = "$w × $h"
                exif.latLong?.let { latLong ->
                    latitude = latLong[0]
                    longitude = latLong[1]
                }
            }
        } catch (_: Exception) {
            // No EXIF block, unsupported format, or unreadable stream — leave fields null.
        }

        // EXIF pixel dimensions aren't always present (common for received images whose
        // EXIF was stripped by a carrier) — fall back to a bounds-only bitmap decode,
        // which reads the image header without allocating the full bitmap.
        if (dimensions == null) {
            try {
                context.contentResolver.openInputStream(parsed)?.use { input ->
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeStream(input, null, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        dimensions = "${opts.outWidth} × ${opts.outHeight}"
                    }
                }
            } catch (_: Exception) { /* leave dimensions null */ }
        }

        val fileSizeLabel = try {
            context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { afd ->
                formatFileSize(afd.length)
            }
        } catch (_: Exception) { null }

        ImageMetadata(dateTaken, camera, dimensions, latitude, longitude, fileSizeLabel)
    }

private fun formatFileSize(bytes: Long): String? {
    if (bytes <= 0) return null
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}

/** One page of [FullScreenImageViewer]: a Coil image with pinch-to-zoom and pan.
 *  Zoom state is per-page, so paging to another image resets to 1×. */
@Composable
private fun ZoomableImage(uri: String, modifier: Modifier = Modifier) {
    // Zoom and pan state — tracked as mutable floats so graphicsLayer can read them
    // without triggering a full recomposition on every gesture frame.
    var scale   by remember(uri) { mutableStateOf(1f) }
    var offsetX by remember(uri) { mutableStateOf(0f) }
    var offsetY by remember(uri) { mutableStateOf(0f) }

    val ctx = LocalContext.current
    // Remembered: rebuilding the request per recomposition re-triggered request
    // equality checks on every gesture frame while zooming.
    val imageRequest = remember(uri) {
        ImageRequest.Builder(ctx)
            .data(Uri.parse(uri))
            .crossfade(true)
            .build()
    }
    SubcomposeAsyncImage(
        model = imageRequest,
        contentDescription = "Full-screen photo",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxSize()
            // Consume tap on the image itself so it doesn't fall through to the scrim.
            .clickable { /* absorb — don't dismiss when tapping the image */ }
            // Pinch-to-zoom + pan gesture. Hand-rolled instead of detectTransformGestures()
            // because that consumes every single-finger drag unconditionally — including a
            // plain swipe attempt — which starved the parent HorizontalPager of the gesture
            // and made thread-wide swiping silently do nothing. Only consume (and treat as
            // zoom/pan) when a second finger is actually down or the image is already
            // zoomed in; a lone finger at 1× scale falls through untouched so the pager's
            // own drag detection sees it and pages normally.
            .pointerInput(uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val isPinch = event.changes.size > 1
                        if (isPinch || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                // Reset pan when fully zoomed out.
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Lambda overload — reads scale/offset in the draw/layer phase only. The
            // parameter overload read them during composition, recomposing this whole
            // node on every pinch/pan frame (despite the comment above claiming otherwise).
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            }
    )
}

// ── Screen-orientation helpers ────────────────────────────────────────────────

/** Walk the ContextWrapper chain to find the hosting [Activity], or null. */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * While this composable is in the composition, let the screen rotate freely
 * (subject to the user's system auto-rotate setting). On dispose it restores the
 * app-wide portrait lock declared in the manifest.
 *
 * The full-screen media viewers use this so landscape photos/videos can be viewed
 * rotated, while the rest of the app stays portrait. Rotation reconfigures the
 * activity in place (see `configChanges` in the manifest) rather than recreating
 * it, so the viewer stays open across the rotation.
 */
@Composable
private fun AllowScreenRotationWhileVisible() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

// ── VideoPlayerDialog ─────────────────────────────────────────────────────────

/**
 * Full-screen dialog that plays an MMS video using ExoPlayer.
 *
 * Shows the video above a Compose control bar that mirrors the audio chip:
 * play/pause button, scrubable Slider, and elapsed / total timestamps.
 * The native PlayerView controls are hidden so only the Compose bar is visible.
 *
 * @param uri       content://mms/part/ URI for the video to play.
 * @param onDismiss Called when the user closes the player.
 */
@Composable
private fun VideoPlayerDialog(uri: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // Allow the device to rotate while the player is open (portrait clips can then be
    // watched full-screen in landscape); reverts to the app-wide portrait lock on close.
    AllowScreenRotationWhileVisible()
    // Read from the Activity window (this composable's own hosting window, before the
    // Dialog{} call below enters its own separate window) — see the identical comment
    // on FullScreenImageViewer's navBarBottomPadding param for why this is more
    // reliable than navigationBarsPadding() applied inside the dialog itself.
    val navBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Playback state mirrored into Compose so the control bar recomposes correctly.
    var isPlaying  by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    // Normalised position 0f..1f; durationMs=0 until the player signals READY.
    var position   by remember { mutableStateOf(0f) }
    var durationMs by remember { mutableLongStateOf(0L) }

    // Center flash cue: each tap-to-toggle pops the new-state icon in the middle of the
    // video and fades it out, so the user sees confirmation beyond the frame just
    // starting/stopping. flashTick bumps on every tap to (re)trigger the fade.
    var flashIcon by remember { mutableStateOf(Icons.Default.PlayArrow) }
    var flashTick by remember { mutableStateOf(0) }
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flashTick) {
        if (flashTick == 0) return@LaunchedEffect   // no flash on first composition
        flashAlpha.snapTo(1f)
        delay(350)
        flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 250))
    }

    // Build and prepare the player once.
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            prepare()
            playWhenReady = true
        }
    }

    // Mirror ExoPlayer state into Compose variables via a listener.
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY && durationMs == 0L) {
                    durationMs = player.duration.coerceAtLeast(0L)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Poll currentPosition every 200 ms while playing (same pattern as audio chip).
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            if (!isScrubbing && durationMs > 0L) {
                position = player.currentPosition.toFloat() / durationMs
            }
            delay(200)
        }
    }

    // Format milliseconds as "m:ss" — same helper used in audio chip.
    fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // See the identical comment in FullScreenImageViewer — without this, this dialog's
        // own Window never reports real navigationBars insets, so navigationBarsPadding()
        // below would silently compute zero padding on tall/short-aspect videos where the
        // control bar ends up flush with the bottom edge.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(bottom = navBarBottomPadding),
            verticalArrangement = Arrangement.Center
        ) {
            // Video surface — native controls hidden; we supply our own bar below.
            // Fill all vertical space left above the control bar; PlayerView's default
            // RESIZE_MODE_FIT preserves the video's own aspect ratio, so a portrait clip
            // uses the full height instead of being letterboxed into a 16:9 strip.
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            this.player = player
                            // Hide ExoPlayer's built-in control bar.
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // Transparent tap layer over the video: tapping anywhere on the frame
                // toggles play/pause (industry-standard, and a far larger target than the
                // control-bar button). Sits above the PlayerView but below the close button
                // so the corner ✕ still dismisses. No ripple — an indication over video reads
                // as a glitch.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            val willPlay = !player.isPlaying
                            if (willPlay) player.play() else player.pause()
                            flashIcon = if (willPlay) Icons.Default.PlayArrow else Icons.Default.Pause
                            flashTick++
                        }
                )
                // Momentary play/pause cue from tapping the frame. No pointer modifier, so
                // it never intercepts taps — they fall through to the layer above. Hidden
                // (alpha 0) at rest; the fade pops it up briefly on each toggle.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(76.dp)
                        .graphicsLayer {
                            alpha = flashAlpha.value
                            // Gentle pop-out: grows slightly as it fades.
                            val s = 1f + (1f - flashAlpha.value) * 0.25f
                            scaleX = s
                            scaleY = s
                        }
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = flashIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
                // Close button in top-right corner.
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            // ── Control bar ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Play / Pause toggle.
                IconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    // Scrubable progress slider.
                    Slider(
                        value = position,
                        onValueChange = { newVal ->
                            isScrubbing = true
                            position = newVal
                        },
                        onValueChangeFinished = {
                            if (durationMs > 0L) {
                                player.seekTo((position * durationMs).toLong())
                            }
                            isScrubbing = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor         = Color.White,
                            activeTrackColor   = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    // Elapsed time (left) and total duration (right).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text  = if (durationMs > 0L) fmtMs((position * durationMs).toLong()) else "0:00",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            text  = if (durationMs > 0L) fmtMs(durationMs) else "Video",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays the emoji reaction chips for a single message.
 *
 * Reactions are grouped by emoji; each group shows the emoji + a count when > 1 reactor.
 * Chips the local user has added (senderAddress == [SELF_ADDRESS]) use a highlighted style.
 *
 * Uses [FlowRow] so that chips wrap to a second line instead of overflowing the bubble width.
 *
 * @param reactions      Full list of [Reaction] objects on the message.
 * @param onReactionClick  Called with the emoji string when a chip is tapped (toggles the reaction).
 * @param modifier       Receives the corner-straddle placement from [MessageBubble].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionPills(
    reactions: List<Reaction>,
    onReactionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val grouped = remember(reactions) { reactions.groupBy { it.emoji } }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        grouped.forEach { (emoji, reactors) ->
            val iMine = reactors.any { it.senderAddress == SELF_ADDRESS }
            val count = reactors.size
            val label = if (count > 1) "$emoji $count" else emoji
            Surface(
                onClick = { onReactionClick(emoji) },
                shape = RoundedCornerShape(10.dp),
                color = if (iMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(
                    width = if (iMine) 1.dp else 0.5.dp,
                    color = if (iMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ── EmojiReactionPopup ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiReactionPopup(
    message: Message,
    quickEmojis: List<String>,
    bubbleBottomY: Float,
    onReact: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val myReactionEmojis = remember(message.reactions) {
        message.reactions.filter { it.senderAddress == SELF_ADDRESS }.map { it.emoji }.toSet()
    }
    var showMoreSheet by remember { mutableStateOf(false) }

    // Pill geometry — 44dp button + 6dp × 2 vertical padding = 56dp
    val pillHeightPx   = with(density) { 56.dp.toPx() }
    val gapPx          = with(density) { 8.dp.toPx() }
    // Maximum Y for the pill top: screen height minus pill size and a small bottom margin.
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val maxPillTopPx   = screenHeightPx - pillHeightPx - gapPx - with(density) { 16.dp.toPx() }

    // bubbleBottomY is the live position fed from MessageBubble's onGloballyPositioned
    // (via liveBubbleY in ThreadContent), so it is always up to date — no manual
    // IME or top-bar offset compensation is needed here.
    val pillTopPx = reactionPillTopPx(bubbleBottomY, gapPx, maxPillTopPx)

    // Theme-driven so the pill matches the app theme instead of always rendering dark
    // (it previously used near-black literals that looked wrong on the Always-Light theme).
    val pillBg     = MaterialTheme.colorScheme.surfaceContainerHigh
    val pillBorder = MaterialTheme.colorScheme.outlineVariant
    val moreTint   = MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.fillMaxSize()) {
        // Scrim — covers only below the action bar so the action bar stays
        // fully visible and tappable. Tap anywhere in the scrim to dismiss.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 56.dp)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        // Emoji pill — 5 emoji + more button, floats above (or below) the bubble
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, pillTopPx.toInt()) },
            shape = RoundedCornerShape(24.dp),
            color = pillBg,
            border = BorderStroke(0.5.dp, pillBorder),
            shadowElevation = 8.dp,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                quickEmojis.forEach { emoji ->
                    val isSelected = emoji in myReactionEmojis
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable { onReact(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 24.sp)
                    }
                }
                // More button — opens extended picker
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { showMoreSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "More emoji",
                        tint = moreTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showMoreSheet) {
        EmojiPickerBottomSheet(
            onEmojiSelected = { emoji -> onReact(emoji) },
            onDismiss = { showMoreSheet = false }
        )
    }
}

@Preview(showBackground = true, device = Devices.PIXEL_7, showSystemUi = true)
@Preview(showBackground = true, device = Devices.PIXEL_7, showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark Mode")
@Composable
private fun ThreadScreenPreview() {
    PostmarkTheme {
        val now = System.currentTimeMillis()
        val sampleThread = Thread(
            id = 1,
            displayName = "Sarah Johnson",
            address = "+1234567890",
            lastMessageAt = now
        )
        val sampleMessages = listOf(
            Message(1, 1, "+1234567890", "We need to do that more often", now - 7200000, false, 1),
            Message(2, 1, "self", "100%. Also sorry for keeping you out so late haha", now - 7000000, true, 1),
            Message(3, 1, "+1234567890", "Are you kidding, best night I've had in months", now - 6800000, false, 1),
            Message(4, 1, "+1234567890", "Heads up — I'm making a big batch of soup, want some?", now - 3600000, false, 1),
            Message(5, 1, "self", "Wait seriously? Yes please 🙏", now - 3500000, true, 1,
                reactions = listOf(Reaction(1, 5, "+1234567890", "❤️", now - 3400000, "Loved 'Wait seriously? Yes please 🙏'"))),
            Message(6, 1, "+1234567890", "I'll drop some off around 6 if that works", now - 3000000, false, 1),
            Message(7, 1, "self", "Perfect, I'll be home. You're an angel", now - 2800000, true, 1),
            Message(8, 1, "+1234567890", "Hey, are you coming to the tonight?", now - 1000000, false, 1)
        )
        ThreadContent(
            uiState = ThreadUiState(
                thread = sampleThread,
                messages = sampleMessages
            ),
            timestampPref = TimestampPreference.ALWAYS,
            activeDates = setOf(LocalDate.now(), LocalDate.now().minusDays(1)),
            quickReactionEmojis = listOf("❤️", "😂", "😮", "😢", "🙏", "👍"),
            onBack = {},
            onViewStats = {},
            onHighlightMessage = {},
            onDismissDefaultSmsDialog = {},
            onUpdateBackupPolicy = {},
            onDismissReactionPicker = {},
            onEnterSelectionModeFromActionMode = {},
            onForwardMessage = {},
            onExitSelectionMode = {},
            onSetSelectionScope = {},
            onToggleMute = {},
            onTogglePin = {},
            onToggleNotifications = {},
            onEnterSelectionMode = {},
            onReplyTextChanged = {},
            onSendMessage = {},
            onToggleSelection = {},
            onShowReactionPicker = { _, _ -> },
            onToggleReaction = { _, _ -> },
            onToggleTimestamp = {},
            onToggleMessageIds = {},
            onRetry = {}
        )
    }
}

// ── EmojiPickerBottomSheet ────────────────────────────────────────────────────

/**
 * Full emoji picker — [androidx.emoji2.emojipicker.EmojiPickerView], the same widget
 * Google ships for this exact purpose (category tabs, the complete Unicode emoji set,
 * recents, long-press for skin-tone/gender variants). Replaces a hand-curated ~47-emoji
 * list (four sections, keyword search over just those) that was a poor substitute for
 * "the emoji keyboard my phone already has" — this is the real thing, not a lookalike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerBottomSheet(
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        AndroidView(
            factory = { context ->
                androidx.emoji2.emojipicker.EmojiPickerView(context).apply {
                    setOnEmojiPickedListener { item -> onEmojiSelected(item.emoji) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 400.dp)
                .navigationBarsPadding()
        )
    }
}

// ── MessageActionTopBar ───────────────────────────────────────────────────────

@Composable
private fun MessageActionTopBar(
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onSelect: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionItem(Icons.Default.Close,            "Cancel",  onCancel,  MaterialTheme.colorScheme.error)
            ActionItem(Icons.Default.ContentCopy,      "Copy",    onCopy)
            ActionItem(Icons.Default.CheckBox,         "Select",  onSelect)
            ActionItem(Icons.AutoMirrored.Filled.Send, "Forward", onForward)
            ActionItem(Icons.Default.Delete,           "Delete",  onDelete,  MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.Unspecified
) {
    val effectiveTint = if (tint.isUnspecified) MaterialTheme.colorScheme.onSurface else tint
    Column(
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = effectiveTint,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = effectiveTint
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Scroll effect helpers — extracted to dedicated composables so each LaunchedEffect
// only restarts when its own keys change, not when any ThreadContent state changes.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Scrolls the list to the bottom whenever a scroll-to-bottom event is emitted.
 * Triggered by the user sending a message.
 */
@Composable
private fun ThreadScrollToBottomEffect(
    scrollToBottomEvent: kotlinx.coroutines.flow.Flow<Unit>,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    LaunchedEffect(Unit) {
        scrollToBottomEvent.collect { listState.animateScrollToItem(0) }
    }
}

/**
 * Watches the total message count. If the user is already near the bottom, auto-scrolls
 * to show the new message. Otherwise raises the scroll-to-bottom FAB briefly (~3 s).
 */
@Composable
private fun ThreadNewMessageScrollEffect(
    messageCount: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onFabVisible: (Boolean) -> Unit
) {
    LaunchedEffect(messageCount) {
        if (messageCount == 0) return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        } else {
            onFabVisible(true)
            kotlinx.coroutines.delay(3_000)
            onFabVisible(false)
        }
    }
}
