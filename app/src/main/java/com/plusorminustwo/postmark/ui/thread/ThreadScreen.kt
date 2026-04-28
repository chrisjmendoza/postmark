package com.plusorminustwo.postmark.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.app.role.RoleManager
import android.provider.Telephony
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.ui.components.LetterAvatar
import com.plusorminustwo.postmark.domain.formatter.ExportFormatter
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset

private val PILL_HIDE_DELAY_MS = 1_800L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(
    threadId: Long,
    scrollToMessageId: Long = -1L,
    scrollToDate: String = "",
    onBack: () -> Unit,
    onViewStats: () -> Unit = {},
    onBackupSettingsClick: () -> Unit = {},
    viewModel: ThreadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val timestampPref by viewModel.timestampPreference.collectAsState()
    val activeDates by viewModel.activeDates.collectAsState()
    val quickReactionEmojis by viewModel.quickReactionEmojis.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showCalendarPicker by remember { mutableStateOf(false) }
    var showBackupPolicyDialog by remember { mutableStateOf(false) }

    // ── Scroll to message (search-jump) ───────────────────────────────────────

    LaunchedEffect(scrollToMessageId) {
        if (scrollToMessageId == -1L) return@LaunchedEffect
        val messages = snapshotFlow { uiState.messages }
            .first { it.any { msg -> msg.id == scrollToMessageId } }
        val localGrouped = messages.groupByDay()
        val localReversedByDate = localGrouped.mapValues { (_, msgs) -> msgs.reversed() }
        var itemIndex = 0
        var targetIndex = -1
        localGrouped.entries.reversed().forEach { (dateLabel, dayMessages) ->
            val msgs = localReversedByDate[dateLabel] ?: emptyList()
            msgs.forEach { msg ->
                if (msg.id == scrollToMessageId) targetIndex = itemIndex
                itemIndex++
            }
            itemIndex++ // header item
        }
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
            viewModel.highlightMessage(scrollToMessageId)
        }
    }

    // ── Floating date pill ────────────────────────────────────────────────────

    var pillVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) pillVisible = true
                else { delay(PILL_HIDE_DELAY_MS); pillVisible = false }
            }
    }

    // ── Derived display state ─────────────────────────────────────────────────

    val grouped = remember(uiState.messages) { uiState.messages.groupByDay() }

    val reversedByDate = remember(grouped) {
        grouped.mapValues { (_, msgs) -> msgs.reversed() }
    }

    val messageIdToDate = remember(grouped) {
        grouped.flatMap { (label, msgs) -> msgs.map { it.id to label } }.toMap()
    }

    val dateToHeaderIndex = remember(grouped) { buildDateToHeaderIndex(grouped) }

    val messageIdToIndex = remember(grouped) {
        var idx = 0
        buildMap<Long, Int> {
            grouped.entries.reversed().forEach { (dateLabel, _) ->
                val msgs = reversedByDate[dateLabel] ?: emptyList()
                msgs.forEachIndexed { i, msg -> put(msg.id, idx + i) }
                idx += msgs.size + 1  // +1 for the date header
            }
        }
    }

    val initialScrollDateLabel = remember(scrollToDate) {
        if (scrollToDate.isEmpty()) "" else localDateToLabel(LocalDate.parse(scrollToDate))
    }

    // Cluster positions — pure display, computed once per message-set change.
    val clusterPositions = remember(uiState.messages) {
        computeClusterPositions(uiState.messages)
    }

    val visibleDate by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf ""
            val topItem = visible.maxByOrNull { it.index } ?: return@derivedStateOf ""
            when (val key = topItem.key) {
                is String -> key.removePrefix("header_")
                is Long   -> messageIdToDate[key] ?: ""
                else      -> ""
            }
        }
    }

    var pillDateLabel by remember { mutableStateOf("") }
    if (visibleDate.isNotEmpty()) pillDateLabel = visibleDate

    fun scrollToDateLabel(label: String) {
        dateToHeaderIndex[label]?.let { headerIdx ->
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
            scrollToMessageId != -1L            -> messageIdToIndex[scrollToMessageId]
            initialScrollDateLabel.isNotEmpty() -> dateToHeaderIndex[initialScrollDateLabel]
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
            onDismissRequest = { viewModel.dismissDefaultSmsDialog() },
            title = { Text("Set Postmark as default SMS app") },
            text  = { Text("To send messages, Postmark needs to be your default SMS app.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissDefaultSmsDialog()
                    launchDefaultSmsRoleRequest(context)
                }) { Text("Set as default") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDefaultSmsDialog() }) { Text("Not now") }
            }
        )
    }

    if (showBackupPolicyDialog) {
        BackupPolicyDialog(
            currentPolicy = uiState.thread?.backupPolicy ?: BackupPolicy.GLOBAL,
            onPolicySelected = { policy ->
                viewModel.updateBackupPolicy(policy)
                showBackupPolicyDialog = false
            },
            onDismiss = { showBackupPolicyDialog = false }
        )
    }

    // ── Scaffold + overlay ────────────────────────────────────────────────────

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            when {
                uiState.reactionPickerMessageId != null -> MessageActionTopBar(
                    onCancel  = { viewModel.dismissReactionPicker() },
                    onCopy    = {
                        val msg = uiState.messages.find { it.id == uiState.reactionPickerMessageId }
                        if (msg != null) {
                            val cb = context.getSystemService(ClipboardManager::class.java)
                            cb.setPrimaryClip(ClipData.newPlainText("message", msg.body))
                            scope.launch { snackbarHostState.showSnackbar("Copied", duration = SnackbarDuration.Short) }
                        }
                        viewModel.dismissReactionPicker()
                    },
                    onSelect  = { viewModel.enterSelectionModeFromActionMode() },
                    onForward = { uiState.reactionPickerMessageId?.let { viewModel.forwardMessage(it) } },
                    onDelete  = { viewModel.dismissReactionPicker() }
                )
                uiState.isSelectionMode -> SelectionTopBar(
                    selectedCount = uiState.selectedMessageIds.size,
                    totalMessages = uiState.messages.size,
                    scope = uiState.selectionScope,
                    onClose = { viewModel.exitSelectionMode() },
                    onScopeChange = { viewModel.setSelectionScope(it) },
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
                        viewModel.exitSelectionMode()
                    }
                )
                else -> TopAppBar(
                    title = {
                        val name = uiState.thread?.displayName ?: ""
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LetterAvatar(name = name, size = 36.dp)
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
                                    onClick = { menuExpanded = false; viewModel.enterSelectionMode() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Search in thread") },
                                    onClick = { menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Mute") },
                                    onClick = { menuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Backup settings") },
                                    onClick = { menuExpanded = false; onBackupSettingsClick() }
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
            if (!uiState.isSelectionMode && uiState.reactionPickerMessageId == null) {
                ReplyBar(
                    text = uiState.replyText,
                    onTextChange = { viewModel.onReplyTextChanged(it) },
                    onSend = { viewModel.sendMessage() }
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
                grouped.entries.reversed().forEach { (dateLabel, messages) ->
                    val msgs = reversedByDate[dateLabel] ?: emptyList()
                    items(msgs, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            clusterPosition = clusterPositions[message.id] ?: ClusterPosition.SINGLE,
                            isSelected = message.id in uiState.selectedMessageIds,
                            isSelectionMode = uiState.isSelectionMode,
                            isHighlighted = message.id == uiState.highlightedMessageId,
                            onToggleSelect = { viewModel.toggleSelection(message.id) },
                            onLongClick = { y -> viewModel.showReactionPicker(message.id, y) },
                            onReactionClick = { emoji -> viewModel.toggleReaction(message.id, emoji) },
                            timestampPref = timestampPref,
                            isTimestampExpanded = message.id in uiState.expandedTimestampIds,
                            onToggleTimestamp = { viewModel.toggleTimestamp(message.id) }
                        )
                    }
                    item(key = "header_$dateLabel") {
                        DateHeader(
                            label = dateLabel,
                            isSelectionMode = uiState.isSelectionMode,
                            selectedCount = messages.count { it.id in uiState.selectedMessageIds },
                            totalCount = messages.size,
                            onToggleDay = { viewModel.toggleMessageIds(messages.map { it.id }) }
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
            ScrollToLatestButton(
                visible = scrolledUp,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            )
        }
    }

    // ── Emoji reaction popup (overlays full screen including action bar) ─────────────────

        val reactionPickerMessage = uiState.reactionPickerMessageId?.let { id ->
            uiState.messages.find { it.id == id }
        }
        reactionPickerMessage?.let { msg ->
            EmojiReactionPopup(
                message     = msg,
                quickEmojis = quickReactionEmojis,
                bubbleTopY  = uiState.reactionPickerBubbleY,
                onReact     = { emoji -> viewModel.toggleReaction(msg.id, emoji) },
                onDismiss   = { viewModel.dismissReactionPicker() }
            )
        }
    } // end overlay Box
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
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to latest")
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
    onReactionClick: (String) -> Unit,
    timestampPref: TimestampPreference,
    isTimestampExpanded: Boolean,
    onToggleTimestamp: () -> Unit
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> bubbleRootY[0] = coords.positionInRoot().y }
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
                else if (isHighlighted) Modifier.background(MaterialTheme.colorScheme.tertiaryContainer)
                else Modifier
            )
            .padding(start = 12.dp, end = 12.dp, top = topPadding, bottom = bottomPadding),
        horizontalAlignment = alignment
    ) {
        Box(modifier = Modifier.widthIn(max = 280.dp)) {
            Box(
                modifier = Modifier
                    .background(bubbleColor, bubbleShape(message.isSent, clusterPosition))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(text = message.body, style = MaterialTheme.typography.bodyMedium)
            }
            if (message.reactions.isNotEmpty()) {
                ReactionPills(
                    reactions = message.reactions,
                    isSent = message.isSent,
                    onReactionClick = onReactionClick,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(y = 16.dp)
                        .padding(end = 4.dp)
                )
            }
        }
        // Reserve space for the chip that overhangs the bubble via offset,
        // so the timestamp is always pushed below the chip — not behind it.
        if (message.reactions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
        }
        if (showTimestamp || message.isSent) {
            Row(
                modifier = Modifier
                    .offset(y = if (message.reactions.isNotEmpty()) (-20).dp else 0.dp)
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
                    Text(
                        text = timeFormatter.format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (message.isSent) {
                    DeliveryStatusIndicator(
                        status = message.deliveryStatus,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
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
private fun DeliveryStatusIndicator(status: Int, modifier: Modifier = Modifier) {
    val (icon, tint) = when (status) {
        DELIVERY_STATUS_PENDING   -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        DELIVERY_STATUS_SENT      -> Icons.Default.Done to MaterialTheme.colorScheme.onSurfaceVariant
        DELIVERY_STATUS_DELIVERED -> Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
        DELIVERY_STATUS_FAILED    -> Icons.Default.Error to MaterialTheme.colorScheme.error
        else -> return
    }
    Icon(imageVector = icon, contentDescription = null, modifier = modifier.size(12.dp), tint = tint)
}

// ── ReplyBar ───────────────────────────────────────────────────────────────────

@Composable
private fun ReplyBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val counterText = remember(text.length) { smsCounter(text.length) }
    Column(modifier = modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor   = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        disabledIndicatorColor  = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    shape = RoundedCornerShape(24.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    trailingIcon = counterText?.let { ct ->
                        {
                            Text(
                                text = ct,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (text.length > 160) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(
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
 * Places the pill above the bubble when there is enough room; falls back to below.
 *
 * Extracted as a pure function so it can be unit-tested without Compose.
 */
internal fun reactionPillTopPx(
    bubbleTopY: Float,
    pillHeightPx: Float,
    gapPx: Float,
    minTopPx: Float
): Float = if (bubbleTopY > minTopPx + pillHeightPx + gapPx)
    bubbleTopY - pillHeightPx - gapPx
else
    bubbleTopY + gapPx

private fun smsCounter(length: Int): String? {
    if (length <= 120) return null
    if (length <= 160) return "$length / 160"
    val charsPerPart = 153
    val parts = (length + charsPerPart - 1) / charsPerPart
    return "${(length - 1) / charsPerPart + 1}/$parts"
}

// ── ReactionPills ─────────────────────────────────────────────────────────────

@Composable
private fun ReactionPills(
    reactions: List<Reaction>,
    isSent: Boolean,
    onReactionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val grouped = reactions.groupBy { it.emoji }
    val primaryColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        grouped.forEach { (emoji, reactors) ->
            val iMine = reactors.any { it.senderAddress == SELF_ADDRESS }
            val count = reactors.size
            val label = if (count > 1) "$emoji $count" else emoji
            Surface(
                onClick = { onReactionClick(emoji) },
                shape = RoundedCornerShape(10.dp),
                color = if (iMine) Color(0xFF1A3A5C) else Color(0xFF2C2C2E),
                border = BorderStroke(
                    width = if (iMine) 1.dp else 0.5.dp,
                    color = if (iMine) primaryColor else Color(0xFF3A3A3C)
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

@Composable
private fun EmojiReactionPopup(
    message: Message,
    quickEmojis: List<String>,
    bubbleTopY: Float,
    onReact: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val myReactionEmojis = remember(message.reactions) {
        message.reactions.filter { it.senderAddress == SELF_ADDRESS }.map { it.emoji }.toSet()
    }

    // Pill geometry
    val pillHeightPx = with(density) { 64.dp.toPx() }
    val gapPx        = with(density) { 8.dp.toPx() }
    val minTopPx     = with(density) { 80.dp.toPx() }  // clears action bar + status bar

    val pillTopPx = reactionPillTopPx(bubbleTopY, pillHeightPx, gapPx, minTopPx)

    Box(Modifier.fillMaxSize()) {
        // Scrim — full screen, tap anywhere outside pill dismisses
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        // Emoji pill — horizontally scrollable, floats above (or below) the bubble
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .offset { IntOffset(0, pillTopPx.toInt()) },
            shape = RoundedCornerShape(32.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            LazyRow(
                modifier = Modifier.height(64.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(quickEmojis) { emoji ->
                    val isSelected = emoji in myReactionEmojis
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable { onReact(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 28.sp)
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
    val effectiveTint = if (tint.isUnspecified) LocalContentColor.current else tint
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
