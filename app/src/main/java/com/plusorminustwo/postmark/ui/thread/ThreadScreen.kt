package com.plusorminustwo.postmark.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.app.role.RoleManager
import android.provider.Telephony
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.ui.components.ContactAvatar
import com.plusorminustwo.postmark.ui.components.LetterAvatar
import com.plusorminustwo.postmark.domain.formatter.ExportFormatter
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
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
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

private val PILL_HIDE_DELAY_MS = 1_800L

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
    viewModel: ThreadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val timestampPref by viewModel.timestampPreference.collectAsState()
    val activeDates by viewModel.activeDates.collectAsState()
    val quickReactionEmojis by viewModel.quickReactionEmojis.collectAsState()
    // Bubble font scale — driven by pinch gesture, persisted across sessions.
    val bubbleFontScale by viewModel.bubbleFontScale.collectAsState()

    // ── Stable lambdas ────────────────────────────────────────────────────────
    // Wrapped in remember(viewModel) so the same function reference is reused
    // across recompositions. Prevents child composables that accept lambdas from
    // recomposing just because ThreadScreen recomposed.
    val onHighlightMessage        = remember(viewModel) { { id: Long -> viewModel.highlightMessage(id) } }
    val onDismissDefaultSmsDialog = remember(viewModel) { { viewModel.dismissDefaultSmsDialog() } }
    val onUpdateBackupPolicy      = remember(viewModel) { { policy: BackupPolicy -> viewModel.updateBackupPolicy(policy) } }
    val onDismissReactionPicker   = remember(viewModel) { { viewModel.dismissReactionPicker() } }
    val onEnterSelectionModeFromActionMode = remember(viewModel) { { viewModel.enterSelectionModeFromActionMode() } }
    val onForwardMessage          = remember(viewModel) { { id: Long -> viewModel.forwardMessage(id) } }
    val onExitSelectionMode       = remember(viewModel) { { viewModel.exitSelectionMode() } }
    val onSetSelectionScope       = remember(viewModel) { { scope: SelectionScope -> viewModel.setSelectionScope(scope) } }
    val onToggleMute              = remember(viewModel) { { viewModel.toggleMute() } }
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
    val onAttachmentSelected      = remember(viewModel) { { uri: Uri, mimeType: String -> viewModel.onAttachmentSelected(uri, mimeType) } }
    val onClearAttachment         = remember(viewModel) { { viewModel.clearAttachment() } }
    val onSearchInThread_         = remember(viewModel, threadId, onSearchInThread) { { onSearchInThread(threadId) } }
    val onAdjustFontScale         = remember(viewModel) { { delta: Float -> viewModel.adjustFontScale(delta) } }
    val onResetFontScale          = remember(viewModel) { { viewModel.resetFontScale() } }

    ThreadContent(
        uiState = uiState,
        timestampPref = timestampPref,
        activeDates = activeDates,
        quickReactionEmojis = quickReactionEmojis,
        bubbleFontScale = bubbleFontScale,
        scrollToMessageId = scrollToMessageId,
        scrollToDate = scrollToDate,
        scrollToBottomEvent = viewModel.scrollToBottomEvent,
        onBack = onBack,
        onViewContact = onViewContact,
        onViewStats = onViewStats,
        onBackupSettingsClick = onBackupSettingsClick,
        onHighlightMessage = onHighlightMessage,
        onDismissDefaultSmsDialog = onDismissDefaultSmsDialog,
        onUpdateBackupPolicy = onUpdateBackupPolicy,
        onDismissReactionPicker = onDismissReactionPicker,
        onEnterSelectionModeFromActionMode = onEnterSelectionModeFromActionMode,
        onForwardMessage = onForwardMessage,
        onExitSelectionMode = onExitSelectionMode,
        onSetSelectionScope = onSetSelectionScope,
        onToggleMute = onToggleMute,
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
        onAttachmentSelected = onAttachmentSelected,
        onClearAttachment = onClearAttachment,
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
    scrollToMessageId: Long = -1L,
    scrollToDate: String = "",
    scrollToBottomEvent: SharedFlow<Unit> = MutableSharedFlow(),
    onBack: () -> Unit,
    onViewContact: () -> Unit = {},
    onViewStats: () -> Unit,
    onBackupSettingsClick: () -> Unit,
    onHighlightMessage: (Long) -> Unit,
    onDismissDefaultSmsDialog: () -> Unit,
    onUpdateBackupPolicy: (BackupPolicy) -> Unit,
    onDismissReactionPicker: () -> Unit,
    onEnterSelectionModeFromActionMode: () -> Unit,
    onForwardMessage: (Long) -> Unit,
    onExitSelectionMode: () -> Unit,
    onSetSelectionScope: (SelectionScope) -> Unit,
    onToggleMute: () -> Unit,
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
    onAttachmentSelected: (Uri, String) -> Unit = { _, _ -> },
    onClearAttachment: () -> Unit = {},
    onSearchInThread: () -> Unit = {},
    onAdjustFontScale: (Float) -> Unit = {},
    onResetFontScale: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showCalendarPicker by remember { mutableStateOf(false) }
    var showBackupPolicyDialog by remember { mutableStateOf(false) }
    var showDateRangePicker by remember { mutableStateOf(false) }

    // Live Y of the bubble currently selected for reaction.
    // Initialised from the ViewModel's snapshot Y (captured at long-press time),
    // then updated every layout pass by the selected bubble's onGloballyPositioned.
    // This means the popup always uses the bubble's *current* screen position, so
    // layout changes that happen right after the long-press (top-bar swap, IME
    // dismiss, etc.) are automatically corrected within a single frame.
    var liveBubbleY by remember(uiState.reactionPickerMessageId) {
        mutableFloatStateOf(uiState.reactionPickerBubbleY)
    }

    // ── Scroll to message (search-jump) ───────────────────────────────────────
    // Uses renderState.messageIdToIndex directly — no re-grouping needed.

    LaunchedEffect(scrollToMessageId) {
        if (scrollToMessageId == -1L) return@LaunchedEffect
        // Wait until the target message is present in the render state.
        val targetIndex = snapshotFlow { uiState.renderState.messageIdToIndex[scrollToMessageId] }
            .first { it != null } ?: return@LaunchedEffect
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
        onHighlightMessage(scrollToMessageId)
    }

    // ── New-message auto-scroll / FAB nudge ────────────────────────────────
    // fabVisible is hoisted here so both this effect and the scroll-triggered
    // effect inside the inner Box can drive the same FAB state.
    var fabVisible by remember { mutableStateOf(false) }

    // ── Floating date pill ────────────────────────────────────────────────────
    var pillVisible by remember { mutableStateOf(false) }

    // Scroll effects — each isolated in its own helper composable so unrelated
    // state changes don't trigger other effects unnecessarily.
    ThreadScrollToBottomEffect(scrollToBottomEvent, listState)
    ThreadNewMessageScrollEffect(
        messageCount = uiState.messages.size,
        listState    = listState,
        onFabVisible = { fabVisible = it }
    )
    ThreadFloatingDatePillEffect(
        listState = listState,
        onPillVisible = { pillVisible = it }
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
                is String -> key.removePrefix("header_")
                is Long   -> uiState.renderState.messageIdToDate[key] ?: ""
                else      -> ""
            }
        }
    }

    var pillDateLabel by remember { mutableStateOf("") }
    if (visibleDate.isNotEmpty()) pillDateLabel = visibleDate

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
                    launchDefaultSmsRoleRequest(context)
                }) { Text("Set as default") }
            },
            dismissButton = {
                TextButton(onClick = { onDismissDefaultSmsDialog() }) { Text("Not now") }
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
                    onDelete  = { onDismissReactionPicker() }
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
                            uiState.thread?.displayName ?: "",
                            ""
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
                                DropdownMenuItem(
                                    text = { Text("Block number") },
                                    onClick = { menuExpanded = false }
                                )
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
                    text                 = uiState.replyText,
                    pendingAttachmentUri = uiState.pendingAttachmentUri,
                    pendingMimeType      = uiState.pendingMimeType,
                    onTextChange         = { onReplyTextChanged(it) },
                    onAttachmentSelected = onAttachmentSelected,
                    onClearAttachment    = onClearAttachment,
                    onSend               = { onSendMessage() }
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
                items(uiState.renderState.items, key = { it.key }) { item ->
                    when (item) {
                        is ThreadListItem.Bubble -> MessageBubble(
                            message = item.message,
                            clusterPosition = item.clusterPosition,
                            isSelected = item.message.id in uiState.selectedMessageIds,
                            isSelectionMode = uiState.isSelectionMode,
                            isHighlighted = item.message.id == uiState.highlightedMessageId,
                            onToggleSelect = { onToggleSelection(item.message.id) },
                            onLongClick = { y -> onShowReactionPicker(item.message.id, y) },
                            onReactionTargetYChanged = if (item.message.id == uiState.reactionPickerMessageId)
                                { y -> liveBubbleY = y } else null,
                            onReactionClick = { emoji -> onToggleReaction(item.message.id, emoji) },
                            timestampPref = timestampPref,
                            isTimestampExpanded = item.message.id in uiState.expandedTimestampIds,
                            onToggleTimestamp = { onToggleTimestamp(item.message.id) },
                            onRetry = { onRetry(item.message.id) }
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

            FloatingDatePill(
                dateLabel = pillDateLabel,
                visible = pillVisible,
                onClick = { showCalendarPicker = true },
                modifier = Modifier.align(Alignment.TopCenter)
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

// ── DateRangeBottomSheet ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeBottomSheet(
    onSelect: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState = rememberDateRangePickerState()
    val sheetState  = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        DateRangePicker(
            state    = pickerState,
            headline = null,
            showModeToggle = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                onClick = {
                    val startMs = pickerState.selectedStartDateMillis ?: return@Button
                    val endMs   = pickerState.selectedEndDateMillis   ?: return@Button
                    val start = Instant.ofEpochMilli(startMs).atZone(ZoneOffset.UTC).toLocalDate()
                    val end   = Instant.ofEpochMilli(endMs).atZone(ZoneOffset.UTC).toLocalDate()
                    onSelect(start, end)
                },
                enabled = pickerState.selectedStartDateMillis != null &&
                          pickerState.selectedEndDateMillis   != null
            ) { Text("Select") }
        }
    }
}

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
    onReactionClick: (String) -> Unit,
    timestampPref: TimestampPreference,
    isTimestampExpanded: Boolean,
    onToggleTimestamp: () -> Unit,
    onRetry: () -> Unit = {}
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
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
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
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                else Modifier
            )
            .padding(start = 12.dp, end = 12.dp, top = topPadding, bottom = bottomPadding),
        horizontalAlignment = alignment
    ) {
        Box(modifier = Modifier.widthIn(max = 280.dp)) {
            Box(
                modifier = Modifier
                    .background(bubbleColor, bubbleShape(message.isSent, clusterPosition))
                    .then(
                        // Tighter padding when an attachment fills the bubble edges.
                        if (message.attachmentUri != null)
                            Modifier.padding(4.dp)
                        else
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    .onSizeChanged { bubbleWidthPx = it.width }
            ) {
                if (message.attachmentUri != null) {
                    // Track whether the full-screen image viewer is open.
                    var showImageViewer by remember { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Render the media attachment (image, video, or audio).
                        MmsAttachment(
                            uri = message.attachmentUri,
                            mimeType = message.mimeType,
                            // Only images are tappable; video/audio have their own interactions.
                            onImageClick = if (message.mimeType?.startsWith("image/") == true)
                                { { showImageViewer = true } } else null
                        )
                        // Show caption text below the attachment if present.
                        if (message.body.isNotEmpty()) {
                            val fontScale   = LocalBubbleFontScale.current
                            val linkColor   = MaterialTheme.colorScheme.primary
                            val textColor   = LocalContentColor.current
                            val baseStyle   = MaterialTheme.typography.bodyMedium
                            val ctx         = LocalContext.current
                            val annotated   = remember(message.body, linkColor) {
                                linkifyText(message.body, linkColor)
                            }
                            ClickableText(
                                text = annotated,
                                style = baseStyle.copy(
                                    fontSize = baseStyle.fontSize * fontScale,
                                    color = textColor
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                onClick = { offset ->
                                    annotated.getStringAnnotations("URL", offset, offset)
                                        .firstOrNull()?.let {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.item)))
                                        } ?: annotated.getStringAnnotations("PHONE", offset, offset)
                                        .firstOrNull()?.let {
                                            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${it.item}")))
                                        }
                                }
                            )
                        }
                    }
                    // Full-screen image viewer — shown when the user taps a photo.
                    if (showImageViewer) {
                        FullScreenImageViewer(
                            uri = message.attachmentUri,
                            onDismiss = { showImageViewer = false }
                        )
                    }
                } else {
                    // Plain SMS bubble — linkify URLs and phone numbers.
                    val fontScale  = LocalBubbleFontScale.current
                    val linkColor  = MaterialTheme.colorScheme.primary
                    val textColor  = LocalContentColor.current
                    val baseStyle  = MaterialTheme.typography.bodyMedium
                    val ctx        = LocalContext.current
                    val annotated  = remember(message.body, linkColor) {
                        linkifyText(message.body, linkColor)
                    }
                    ClickableText(
                        text = annotated,
                        style = baseStyle.copy(
                            fontSize = baseStyle.fontSize * fontScale,
                            color = textColor
                        ),
                        onClick = { offset ->
                            annotated.getStringAnnotations("URL", offset, offset)
                                .firstOrNull()?.let {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.item)))
                                } ?: annotated.getStringAnnotations("PHONE", offset, offset)
                                .firstOrNull()?.let {
                                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${it.item}")))
                                }
                        }
                    )
                }
            }
        }
        if (message.reactions.isNotEmpty()) {
            ReactionPills(
                reactions = message.reactions,
                isSent = message.isSent,
                onReactionClick = onReactionClick,
                modifier = Modifier
                    // Offset upward so pills badge the bubble's bottom edge rather
                    // than floating disconnected below it.
                    .offset(y = (-12).dp)
                    .align(if (message.isSent) Alignment.Start else Alignment.End)
                    .padding(
                        start = if (message.isSent) 4.dp else 0.dp,
                        end = if (message.isSent) 0.dp else 4.dp
                    )
                    .then(
                        if (bubbleWidthPx > 0) Modifier.widthIn(max = with(density) { bubbleWidthPx.toDp() })
                        else Modifier
                    )
            )
        }
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

// ── MmsAttachment ─────────────────────────────────────────────────────────────
// Renders the media content of an MMS message inside the bubble. Images use
// Coil AsyncImage (content://mms/part/ URIs are readable by the default SMS app).
// Video shows a play-icon placeholder. Audio shows a labelled chip.

@Composable
private fun MmsAttachment(
    uri: String,
    mimeType: String?,
    // Non-null when the image can be tapped to open the full-screen viewer.
    onImageClick: (() -> Unit)? = null
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
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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
            /* Normalised playback position 0f..1f, and total duration in ms.
             * durationMs stays 0 until the player is prepared for the first time. */
            var position   by remember { mutableStateOf(0f) }
            var durationMs by remember { mutableStateOf(0) }
            val playerRef  = remember { mutableStateOf<MediaPlayer?>(null) }

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
                                    try {
                                        mp.setDataSource(ctx, Uri.parse(uri))
                                        mp.prepare()
                                        durationMs = mp.duration
                                        // Honour any position the user scrubbed to before pressing play.
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
                                    } catch (e: Exception) {
                                        android.util.Log.e("AudioPlayer", "Playback failed for $uri", e)
                                        mp.release()
                                        playerRef.value = null
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(24.dp)
                        )
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
    // Colored ticks: yellow = sent to carrier, green = delivered to device, red = failed.
    val sentColor      = Color(0xFFFFCC00)   // amber-yellow
    val deliveredColor = Color(0xFF4CAF50)   // material green
    val (icon, tint) = when (status) {
        DELIVERY_STATUS_PENDING   -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        DELIVERY_STATUS_SENT      -> Icons.Default.Done to sentColor
        DELIVERY_STATUS_DELIVERED -> Icons.Default.DoneAll to deliveredColor
        DELIVERY_STATUS_FAILED    -> Icons.Default.Error to MaterialTheme.colorScheme.error
        else -> return
    }
    val clickableModifier = if (status == DELIVERY_STATUS_FAILED && onRetry != null)
        modifier.size(12.dp).clickable(onClick = onRetry)
    else
        modifier.size(12.dp)
    Icon(imageVector = icon, contentDescription = null, modifier = clickableModifier, tint = tint)
}

// ── ReplyBar ───────────────────────────────────────────────────────────────────
// Bottom compose bar with text input, optional attachment preview chip,
// attach button (photo / audio picker), and send button.

@Composable
private fun ReplyBar(
    text: String,
    pendingAttachmentUri: String?,
    pendingMimeType: String?,
    onTextChange: (String) -> Unit,
    onAttachmentSelected: (Uri, String) -> Unit,
    onClearAttachment: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAttachMenu by remember { mutableStateOf(false) }

    // Single launcher; the MIME filter is set per menu item.
    val mediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            onAttachmentSelected(uri, mimeType)
        }
    }

    // Friendly label for the attachment preview chip (used for audio/other only).
    val attachLabel: String? = when {
        pendingMimeType?.startsWith("image/") == true  -> null  // replaced by thumbnail
        pendingMimeType?.startsWith("video/") == true  -> null  // replaced by thumbnail
        pendingMimeType?.startsWith("audio/") == true  -> "🎵 Audio"
        pendingAttachmentUri != null                    -> "📎 Attachment"
        else                                            -> null
    }

    // Only show the SMS counter when no attachment is pending (pure SMS mode).
    val counterText = if (pendingAttachmentUri == null) {
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
                // Attachment preview — thumbnail for images/video, chip for audio/other.
                if (pendingAttachmentUri != null && pendingMimeType?.startsWith("image/") == true) {
                    /* Show a real thumbnail so the user can confirm which photo is attached
                     * before hitting send. The × badge sits at the top-right corner. */
                    Box(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .size(80.dp)
                    ) {
                        SubcomposeAsyncImage(
                            model               = Uri.parse(pendingAttachmentUri),
                            contentDescription  = "Attachment preview",
                            contentScale        = ContentScale.Crop,
                            modifier            = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                        )
                        // Close button overlaid at the top-right corner.
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable(onClick = onClearAttachment),
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
                } else if (pendingAttachmentUri != null && pendingMimeType?.startsWith("video/") == true) {
                    /* Extract the first frame via MediaMetadataRetriever so the user can
                     * see which video is attached. Runs on IO thread; shows a play-icon
                     * placeholder while loading (or if extraction fails). */
                    val videoThumb by produceState<Bitmap?>(null, pendingAttachmentUri) {
                        value = withContext(Dispatchers.IO) {
                            val retriever = MediaMetadataRetriever()
                            var frame: Bitmap? = null
                            try {
                                retriever.setDataSource(context, Uri.parse(pendingAttachmentUri))
                                frame = retriever.getFrameAtTime(0)
                            } catch (e: Exception) {
                                android.util.Log.w("VideoThumb", "Frame extraction failed", e)
                            } finally {
                                retriever.release()
                            }
                            frame
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .size(80.dp)
                    ) {
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
                        // Close button at top-right.
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable(onClick = onClearAttachment),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove attachment",
                                modifier           = Modifier.size(12.dp),
                                tint               = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else if (attachLabel != null) {
                    // Non-image, non-video attachment (audio, generic file) — keep chip.
                    InputChip(
                        selected     = false,
                        onClick      = {},
                        label        = { Text(attachLabel, style = MaterialTheme.typography.bodySmall) },
                        trailingIcon = {
                            IconButton(
                                onClick  = onClearAttachment,
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove attachment",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        },
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
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
                                text    = { Text("Photo or video") },
                                onClick = {
                                    showAttachMenu = false
                                    mediaLauncher.launch("image/*")
                                }
                            )
                            DropdownMenuItem(
                                text    = { Text("Audio file") },
                                onClick = {
                                    showAttachMenu = false
                                    mediaLauncher.launch("audio/*")
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
                        // Enabled when there is text OR a media attachment is pending.
                        enabled  = text.isNotBlank() || pendingAttachmentUri != null,
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

private fun launchDefaultSmsRoleRequest(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(RoleManager::class.java)
        context.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
    } else {
        @Suppress("DEPRECATION")
        context.startActivity(
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(
                Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName
            )
        )
    }
}

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
private fun linkifyText(text: String, linkColor: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        append(text)

        // ── URLs ──────────────────────────────────────────────────────────────
        val urlMatcher = android.util.Patterns.WEB_URL.matcher(text)
        val urlRanges  = mutableListOf<IntRange>()
        while (urlMatcher.find()) {
            val start = urlMatcher.start()
            val end   = urlMatcher.end()
            urlRanges += start until end
            addStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                start = start, end = end
            )
            addStringAnnotation("URL", annotation = urlMatcher.group(), start = start, end = end)
        }

        // ── Phone numbers ─────────────────────────────────────────────────────
        val phoneMatcher = android.util.Patterns.PHONE.matcher(text)
        while (phoneMatcher.find()) {
            val start = phoneMatcher.start()
            val end   = phoneMatcher.end()
            // Skip phone matches that overlap a URL match (e.g. number in a URL path).
            if (urlRanges.any { start < it.last && end > it.first }) continue
            addStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                start = start, end = end
            )
            addStringAnnotation("PHONE", annotation = phoneMatcher.group(), start = start, end = end)
        }
    }
}

// ── FullScreenImageViewer ─────────────────────────────────────────────────────

/**
 * Full-screen overlay that displays an MMS image with pinch-to-zoom support.
 *
 * The image is shown on a black scrim. The user can:
 *  - Pinch to zoom (1× – 5×)
 *  - Pan while zoomed
 *  - Tap the scrim or press Back to dismiss
 *
 * @param uri      content://mms/part/ URI for the image to display.
 * @param onDismiss Called when the user closes the viewer.
 */
@Composable
private fun FullScreenImageViewer(uri: String, onDismiss: () -> Unit) {
    // Zoom and pan state — tracked as mutable floats so graphicsLayer can read them
    // without triggering a full recomposition on every gesture frame.
    var scale     by remember { mutableStateOf(1f) }
    var offsetX   by remember { mutableStateOf(0f) }
    var offsetY   by remember { mutableStateOf(0f) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Tap outside the image (on the scrim) dismisses the viewer.
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
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
                    // Pinch-to-zoom + pan gesture.
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // Clamp scale between 1× and 5×.
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            // Pan only makes sense when zoomed in.
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                // Reset pan when fully zoomed out.
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
            // Close button in the top-right corner as a fallback affordance.
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

    val pillBg     = Color(0xFF2C2C2E)
    val pillBorder = Color(0xFF3A3A3C)
    val moreTint   = Color(0xFF8E8E93)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerBottomSheet(
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var query by remember { mutableStateOf("") }

    val filteredSections = remember(query) {
        if (query.isEmpty()) {
            ALL_EMOJI_SECTIONS
        } else {
            val q = query.trim().lowercase()
            ALL_EMOJI_SECTIONS.mapNotNull { section ->
                val filtered = section.emojis.filter { (_, keywords) -> keywords.contains(q) }
                if (filtered.isNotEmpty()) section.copy(emojis = filtered) else null
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search emoji...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor   = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor   = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor  = Color.Transparent,
                ),
                shape = RoundedCornerShape(12.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 300.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                filteredSections.forEach { section ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = section.name.uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = Color(0xFF8E8E93),
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    lazyGridItems(section.emojis) { (emoji, _) ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clickable { onEmojiSelected(emoji) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                }
            }
        }
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
 * to show the new message. Otherwise raises the FAB for [PILL_HIDE_DELAY_MS]-ish ms.
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

/**
 * Shows the floating date pill while the list is scrolling, then hides it
 * after [PILL_HIDE_DELAY_MS] of inactivity.
 */
@Composable
private fun ThreadFloatingDatePillEffect(
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPillVisible: (Boolean) -> Unit
) {
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) onPillVisible(true)
                else { kotlinx.coroutines.delay(PILL_HIDE_DELAY_MS); onPillVisible(false) }
            }
    }
}
