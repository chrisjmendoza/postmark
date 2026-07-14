package com.plusorminustwo.postmark.ui.thread

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.app.role.RoleManager
import android.provider.MediaStore
import android.provider.Telephony
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.ui.components.ContactAvatar
import com.plusorminustwo.postmark.ui.components.DateRangeBottomSheet
import com.plusorminustwo.postmark.ui.components.LetterAvatar
import com.plusorminustwo.postmark.domain.formatter.ExportFormatter
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.Devices
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.ui.theme.PostmarkTheme
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as lazyGridItems
import androidx.compose.foundation.lazy.itemsIndexed as lazyRowItemsIndexed
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
 * @param onBackupSettingsClick Navigates to the Backup Settings screen.
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
    onBackupSettingsClick: () -> Unit = {},
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

    // ── Stable lambdas ────────────────────────────────────────────────────────
    // Wrapped in remember(viewModel) so the same function reference is reused
    // across recompositions. Prevents child composables that accept lambdas from
    // recomposing just because ThreadScreen recomposed.
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
    val onShowReactionPicker      = remember(viewModel) { { id: Long, y: Float -> viewModel.showReactionPicker(id, y) } }
    val onToggleReaction          = remember(viewModel) { { id: Long, emoji: String -> viewModel.toggleReaction(id, emoji) } }
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
        onBackupSettingsClick = onBackupSettingsClick,
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
        onResetFontScale = onResetFontScale
    )
}

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
    onBackupSettingsClick: () -> Unit,
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
    suspend fun scrollToMessageCentered(targetId: Long) {
        // Wait until the target message is present in the render state.
        val targetIndex = snapshotFlow { uiState.renderState.messageIdToIndex[targetId] }
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

    val visibleDate by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf ""
            val topItem = visible.maxByOrNull { it.index } ?: return@derivedStateOf ""
            when (val key = topItem.key) {
                is String -> if (key.startsWith("header_")) key.removePrefix("header_")
                             else key.toLongOrNull()?.let { uiState.renderState.messageIdToDate[it] } ?: ""
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

    var pillDateLabel by remember { mutableStateOf("") }
    when {
        visibleDate.isNotEmpty() -> pillDateLabel = visibleDate
        pillDateLabel.isEmpty()  -> pillDateLabel = newestDayLabel
    }

    // Measured height of the sticky date header. The message list reserves exactly this much
    // space at its top edge (derived from layout, not a hardcoded value, so it adapts to font
    // scale) so no message ever scrolls behind the header.
    var datePillHeightPx by remember { mutableStateOf(0) }
    val datePillHeight = with(LocalDensity.current) { datePillHeightPx.toDp() }

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
            when {
                uiState.reactionPickerMessageId != null -> MessageActionTopBar(
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
                uiState.isSelectionMode -> SelectionTopBar(
                    selectedCount = uiState.selectedMessageIds.size,
                    totalMessages = uiState.messages.size,
                    scope = uiState.selectionScope,
                    onClose = { onExitSelectionMode() },
                    onScopeChange = { onSetSelectionScope(it) },
                    onShowDateRange = { showDateRangePicker = true },
                    onCopy = {
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
                else -> TopAppBar(
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
        },
        bottomBar = {
            // Keep ReplyBar in layout even when picker is open (alpha=0) so the
            // Scaffold doesn't resize and shift message positions.
            if (!uiState.isSelectionMode) {
                ReplyBar(
                    text                  = uiState.replyText,
                    pendingAttachments    = uiState.pendingAttachments,
                    replyingTo            = uiState.replyingToId?.let { id -> uiState.messages.find { it.id == id } },
                    // Sending is still 1:1-only (MMS_AUDIT #6) — thread.address, not the
                    // full roster. Warn rather than silently reply to just one participant.
                    isGroupThread         = (uiState.thread?.participants?.size ?: 0) > 1,
                    onTextChange          = { onReplyTextChanged(it) },
                    onAttachmentsSelected = onAttachmentsSelected,
                    onRemoveAttachment    = onRemoveAttachment,
                    onClearReplyingTo     = onClearReplyingTo,
                    onSend                = { onSendMessage() }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                // Reserve space for the sticky date header at the top edge so messages scroll
                // beneath it rather than behind it. datePillHeight is measured (see below).
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = datePillHeight),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Flat list from renderState — no forEach loops, stable keys, no recomputation.
                items(uiState.renderState.items, key = { it.key }) { item ->
                    when (item) {
                        is ThreadListItem.Bubble -> MessageBubble(
                            message = item.message,
                            clusterPosition = item.clusterPosition,
                            isSelected = item.message.id in uiState.selectedMessageIds,
                            isSelectionMode = uiState.isSelectionMode,
                            isHighlighted = item.message.id == uiState.highlightedMessageId,
                            onToggleSelect = { onToggleSelection(item.message.id) },
                            onImageTap = onImageTap,
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
                            } else null
                        )
                        is ThreadListItem.DateHeader -> DateHeader(
                            label = item.dateLabel,
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

            // Permanent sticky date header pinned under the top bar. Always visible while the
            // thread has messages; `pillDateLabel` tracks the day of the top-of-viewport item
            // (via `visibleDate`), so it updates as day separators scroll past the top edge.
            FloatingDatePill(
                dateLabel = pillDateLabel,
                visible = pillDateLabel.isNotEmpty(),
                onClick = { showCalendarPicker = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onGloballyPositioned { datePillHeightPx = it.size.height }
            )

            val scrolledUp by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

            // Auto-hide the FAB 3 s after the user stops scrolling.
            // fabVisible is hoisted to the outer scope so the message-arrival
            // effect above can also trigger it when the user is reading history.
            LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
                if (scrolledUp) {
                    fabVisible = true
                    delay(3_000)
                    fabVisible = false
                } else {
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
            shadowElevation = 2.dp,
            modifier = Modifier.padding(top = 8.dp)
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
    senderName: String? = null
) {
    val bubbleColor = if (message.isSent)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

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
        modifier = Modifier
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
                        .alpha((swipeOffset.value / thresholdPx).coerceIn(0f, 1f))
                )
            }
            // Bubble content — translated right during swipe, springs back on release.
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .align(if (message.isSent) Alignment.CenterEnd else Alignment.CenterStart)
                    .graphicsLayer { translationX = swipeOffset.value }
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
                    // URI of the video being played; null = player closed. Videos stay a
                    // per-message dialog — only images page thread-wide (see onImageTap).
                    var playingVideoUri by remember { mutableStateOf<String?>(null) }
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
                                    { { playingVideoUri = att.uri } } else null
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
                                                    playingVideoUri = att.uri
                                                }
                                            }
                                        )
                                    }
                                    // Keep a lone last cell at half width.
                                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            others.forEach { att ->
                                MmsAttachment(uri = att.uri, mimeType = att.mimeType)
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
                    // Full-screen video player — shown when the user taps a video thumbnail.
                    playingVideoUri?.let { videoUri ->
                        VideoPlayerDialog(
                            uri = videoUri,
                            onDismiss = { playingVideoUri = null }
                        )
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
                // Google Messages-style badge: always the bubble's own bottom-right
                // corner, whether the bubble is sent or received, hanging half off
                // the edge. Unconstrained in width so a pill row wider than a short
                // bubble grows leftward past its edge instead of wrapping into a
                // stack of single pills.
                ReactionPills(
                    reactions = message.reactions,
                    isSent = message.isSent,
                    onReactionClick = onReactionClick,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 10.dp)
                )
            }
        }  // end Box(widthIn+align)
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
                        text = timeFormatter.format(Date(message.timestamp)),
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
        if (message.reactions.isNotEmpty() && (clusterPosition == ClusterPosition.BOTTOM || clusterPosition == ClusterPosition.SINGLE)) {
            Spacer(Modifier.height(12.dp))
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
            // Video cell — play icon over the placeholder background.
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Video",
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    onVideoClick: (() -> Unit)? = null
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (onVideoClick != null) Modifier.clickable(onClick = onVideoClick)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Video",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Audio ──────────────────────────────────────────────────────────────
        mimeType?.startsWith("audio/") == true -> {
            val ctx = LocalContext.current
            /* Explicit state objects so the AudioFocusRequest listener lambda can
             * read/write them safely from inside the remember {} block. */
            val isPlayingState = remember { mutableStateOf(false) }
            var isPlaying by isPlayingState
            var isScrubbing by remember { mutableStateOf(false) }
            var isPreparing by remember { mutableStateOf(false) }
            /* Normalised playback position 0f..1f, and total duration in ms.
             * durationMs stays 0 until the player is prepared for the first time. */
            var position   by remember { mutableStateOf(0f) }
            var durationMs by remember { mutableStateOf(0) }
            val playerRef  = remember { mutableStateOf<MediaPlayer?>(null) }
            val scope = rememberCoroutineScope()

            val audioManager = remember { ctx.getSystemService(AudioManager::class.java) }
            /* Request audio focus when playback starts. Only react to full AUDIOFOCUS_LOSS
             * (e.g. incoming phone call). AUDIOFOCUS_LOSS_TRANSIENT and _CAN_DUCK are
             * intentionally ignored so notification sounds (new SMS, alarm) do not
             * interrupt a voice memo the user is actively listening to. */
            val focusRequest = remember {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            if (isPlayingState.value) {
                                playerRef.value?.pause()
                                isPlayingState.value = false
                            }
                        }
                        // AUDIOFOCUS_LOSS_TRANSIENT / _CAN_DUCK not handled on purpose.
                    }
                    .build()
            }

            /* Poll currentPosition every 200 ms while playing so the slider tracks
             * progress. The poll is skipped while the user is dragging (isScrubbing)
             * so the thumb doesn't jump back under their finger. */
            LaunchedEffect(isPlaying) {
                while (isPlaying) {
                    val mp = playerRef.value
                    if (mp != null && !isScrubbing && durationMs > 0) {
                        position = mp.currentPosition.toFloat() / durationMs
                    }
                    delay(200)
                }
            }

            DisposableEffect(uri) {
                onDispose {
                    playerRef.value?.release()
                    playerRef.value = null
                    audioManager.abandonAudioFocusRequest(focusRequest)
                }
            }

            /* Format milliseconds as "m:ss". */
            fun fmtMs(ms: Int): String {
                val s = ms / 1000
                return "%d:%02d".format(s / 60, s % 60)
            }

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
                    /* Play / Pause button.
                     * First press: creates and prepares a new MediaPlayer then starts it.
                     * Subsequent presses toggle pause/resume on the same instance.
                     * On completion the player is released and position resets to 0. */
                    IconButton(
                        onClick = {
                            if (isPreparing) return@IconButton
                            if (isPlaying) {
                                playerRef.value?.pause()
                                isPlaying = false
                                audioManager.abandonAudioFocusRequest(focusRequest)
                            } else {
                                val existing = playerRef.value
                                if (existing != null) {
                                    // Resume from paused position.
                                    audioManager.requestAudioFocus(focusRequest)
                                    existing.start()
                                    isPlaying = true
                                } else {
                                    val mp = MediaPlayer()
                                    playerRef.value = mp
                                    isPreparing = true
                                    // prepare() blocks on I/O — run on IO dispatcher to avoid ANR.
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            mp.setDataSource(ctx, Uri.parse(uri))
                                            mp.prepare()
                                            val dur = mp.duration
                                            withContext(Dispatchers.Main) {
                                                durationMs = dur
                                                if (position > 0f && durationMs > 0) {
                                                    mp.seekTo((position * durationMs).toInt())
                                                }
                                                mp.setOnCompletionListener {
                                                    isPlayingState.value = false
                                                    position  = 0f
                                                    playerRef.value?.release()
                                                    playerRef.value = null
                                                    audioManager.abandonAudioFocusRequest(focusRequest)
                                                }
                                                audioManager.requestAudioFocus(focusRequest)
                                                mp.start()
                                                isPlaying = true
                                                isPreparing = false
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("AudioPlayer", "Playback failed for $uri", e)
                                            withContext(Dispatchers.Main) {
                                                mp.release()
                                                playerRef.value = null
                                                isPreparing = false
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (isPreparing) {
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
                        /* Progress slider — draggable even while paused.
                         * onValueChange sets isScrubbing=true so the poll loop won't
                         * overwrite the thumb position during the drag gesture.
                         * onValueChangeFinished commits the seek to the player. */
                        Slider(
                            value    = position,
                            onValueChange = { newVal ->
                                isScrubbing = true
                                position    = newVal
                            },
                            onValueChangeFinished = {
                                val mp = playerRef.value
                                if (mp != null && durationMs > 0) {
                                    mp.seekTo((position * durationMs).toInt())
                                }
                                isScrubbing = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(20.dp),
                            colors = SliderDefaults.colors(
                                thumbColor        = MaterialTheme.colorScheme.onSecondaryContainer,
                                activeTrackColor  = MaterialTheme.colorScheme.onSecondaryContainer,
                                inactiveTrackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.3f)
                            )
                        )
                        // Elapsed time (left) and total duration (right).
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text  = if (durationMs > 0) fmtMs((position * durationMs).toInt()) else "0:00",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                // Show total duration once known; "Voice memo" until first play.
                                text  = if (durationMs > 0) fmtMs(durationMs) else "Voice memo",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
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

// ── DateHeader ─────────────────────────────────────────────────────────────────

@Composable
private fun DateHeader(
    label: String,
    isSelectionMode: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onToggleDay: () -> Unit
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
        modifier = Modifier
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
private const val MAX_MMS_ATTACHMENTS = 5

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAttachMenu by remember { mutableStateOf(false) }

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

    // Only show the SMS counter when no attachment is pending (pure SMS mode).
    val counterText = if (pendingAttachments.isEmpty()) {
        remember(text.length) { smsCounter(text.length) }
    } else null

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
                if (isGroupThread) {
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
                if (replyingTo != null) {
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
                                    if (replyingTo.isSent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary
                                )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text  = if (replyingTo.isSent) "You" else "Them",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (replyingTo.isSent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.tertiary,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            Text(
                                text     = replyingTo.body.ifBlank {
                                    when {
                                        replyingTo.mimeType?.startsWith("image/") == true -> "🖼️ Photo"
                                        replyingTo.mimeType?.startsWith("video/") == true -> "🎬 Video"
                                        replyingTo.mimeType?.startsWith("audio/") == true -> "🎵 Audio"
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
                // Pending-attachment previews — one 80 dp thumbnail per attachment,
                // each with its own × badge, scrolling horizontally when several.
                if (pendingAttachments.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        lazyRowItemsIndexed(pendingAttachments) { index, attachment ->
                            PendingAttachmentPreview(
                                attachment = attachment,
                                onRemove   = { onRemoveAttachment(index) }
                            )
                        }
                    }
                }

                // Text input row: [attach] [field] [send]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
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
                        }
                    }
                    TextField(
                        value         = text,
                        onValueChange = onTextChange,
                        modifier      = Modifier.weight(1f),
                        placeholder   = { Text("Message") },
                        maxLines      = 4,
                        colors        = TextFieldDefaults.colors(
                            focusedContainerColor   = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            focusedIndicatorColor   = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledIndicatorColor  = androidx.compose.ui.graphics.Color.Transparent,
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
                    Spacer(Modifier.width(4.dp))
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
    }
}

// ── PendingAttachmentPreview ───────────────────────────────────────────────────

/**
 * One 80 dp preview tile in the reply bar's pending-attachment row: image thumbnail,
 * video first-frame (with play badge), or a labelled placeholder for audio/other.
 * The × badge at the top-right removes just this attachment.
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
                /* Extract the first frame via MediaMetadataRetriever so the user can
                 * see which video is attached. Runs on IO thread; shows a play-icon
                 * placeholder while loading (or if extraction fails). */
                val videoThumb by produceState<Bitmap?>(null, attachment.uri) {
                    value = withContext(Dispatchers.IO) {
                        val retriever = MediaMetadataRetriever()
                        var frame: Bitmap? = null
                        try {
                            retriever.setDataSource(context, Uri.parse(attachment.uri))
                            frame = retriever.getFrameAtTime(0)
                        } catch (e: Exception) {
                            android.util.Log.w("VideoThumb", "Frame extraction failed", e)
                        } finally {
                            retriever.release()
                        }
                        frame
                    }
                }
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
                // Audio / generic file — labelled placeholder tile.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = if (attachment.mimeType.startsWith("audio/")) "🎵 Audio" else "📎 File",
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

private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault()).also {
    it.timeZone = java.util.TimeZone.getDefault()
}

private fun localDateToLabel(date: LocalDate): String =
    DAY_FORMATTER.format(Date(date.atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond() * 1000))

/**
 * Corner radii for message bubbles.
 * The "sender side" (right for sent, left for received) gets the small corner radius
 * wherever the bubble attaches to its cluster neighbour.
 */
private fun bubbleShape(isSent: Boolean, position: ClusterPosition): RoundedCornerShape {
    val full  = 16.dp
    val small = 4.dp
    return if (isSent) {
        when (position) {
            ClusterPosition.SINGLE -> RoundedCornerShape(full)
            ClusterPosition.TOP    -> RoundedCornerShape(topStart = full, topEnd = full,  bottomEnd = small, bottomStart = full)
            ClusterPosition.MIDDLE -> RoundedCornerShape(topStart = full, topEnd = small, bottomEnd = small, bottomStart = full)
            ClusterPosition.BOTTOM -> RoundedCornerShape(topStart = full, topEnd = small, bottomEnd = full,  bottomStart = full)
        }
    } else {
        when (position) {
            ClusterPosition.SINGLE -> RoundedCornerShape(full)
            ClusterPosition.TOP    -> RoundedCornerShape(topStart = full,  topEnd = full, bottomEnd = full, bottomStart = small)
            ClusterPosition.MIDDLE -> RoundedCornerShape(topStart = small, topEnd = full, bottomEnd = full, bottomStart = small)
            ClusterPosition.BOTTOM -> RoundedCornerShape(topStart = small, topEnd = full, bottomEnd = full, bottomStart = full)
        }
    }
}

/**
 * Calculates the top-Y offset (in pixels) for the emoji reaction pill.
 * Places the pill just below the bubble; clamps so it never goes off the bottom of the screen.
 *
 * [bubbleBottomY] is the Y coordinate of the bubble's bottom edge in root coordinates.
 * [maxPillTopPx]  is the largest allowed top-Y for the pill (screen height − pill height − padding).
 *
 * Extracted as a pure function so it can be unit-tested without Compose.
 */
internal fun reactionPillTopPx(
    bubbleBottomY: Float,
    pillHeightPx: Float,
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
 * @param linkColor Colour applied to detected links; pass [MaterialTheme.colorScheme.primary].
 */
private fun linkifyText(
    text: String,
    linkColor: androidx.compose.ui.graphics.Color,
    context: android.content.Context,
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
                ZoomableImage(uri = images[page].uri)
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
private fun ZoomableImage(uri: String) {
    // Zoom and pan state — tracked as mutable floats so graphicsLayer can read them
    // without triggering a full recomposition on every gesture frame.
    var scale   by remember(uri) { mutableStateOf(1f) }
    var offsetX by remember(uri) { mutableStateOf(0f) }
    var offsetY by remember(uri) { mutableStateOf(0f) }

    val ctx = LocalContext.current
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(ctx)
            .data(Uri.parse(uri))
            .crossfade(true)
            .build(),
        contentDescription = "Full-screen photo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
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
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
    )
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
            Box(modifier = Modifier.weight(1f, fill = false)) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            this.player = player
                            // Hide ExoPlayer's built-in control bar.
                            useController = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
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
 * The caller constrains [modifier] with `widthIn(max = bubbleWidth)` to enforce the boundary.
 *
 * @param reactions      Full list of [Reaction] objects on the message.
 * @param isSent         Affects alignment — sent bubbles pin chips to the start edge, received to end.
 * @param onReactionClick  Called with the emoji string when a chip is tapped (toggles the reaction).
 * @param modifier       Receives the `widthIn` + alignment constraints from [MessageBubble].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionPills(
    reactions: List<Reaction>,
    isSent: Boolean,
    onReactionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val grouped = reactions.groupBy { it.emoji }
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
    val pillTopPx = reactionPillTopPx(bubbleBottomY, pillHeightPx, gapPx, maxPillTopPx)

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
            onBackupSettingsClick = {},
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
