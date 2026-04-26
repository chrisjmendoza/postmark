package com.plusorminustwo.postmark.ui.thread

import android.content.Intent
import android.os.Build
import android.app.role.RoleManager
import android.provider.Telephony
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.ui.export.ExportBottomSheet
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private val PILL_HIDE_DELAY_MS = 1_800L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    threadId: Long,
    onBack: () -> Unit,
    viewModel: ThreadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val timestampPref by viewModel.timestampPreference.collectAsState()
    val activeDates by viewModel.activeDates.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showExportSheet by remember { mutableStateOf(false) }
    var showCalendarPicker by remember { mutableStateOf(false) }

    // Pill show/hide: collectLatest on a snapshotFlow so that rapid
    // isScrollInProgress oscillations during flings don't restart the
    // coroutine via LaunchedEffect key-change, which could briefly set
    // pillVisible = false before the scroll continuation fires.
    var pillVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) {
                    pillVisible = true
                } else {
                    delay(PILL_HIDE_DELAY_MS)
                    pillVisible = false
                }
            }
    }

    // Build maps that mirror the LazyColumn item structure.
    // grouped preserves insertion order: oldest date → newest date.
    val grouped = remember(uiState.messages) { uiState.messages.groupByDay() }

    // Pre-reverse each day's message list once so the LazyColumn DSL block
    // never allocates a new List per composition pass.
    val reversedByDate = remember(grouped) {
        grouped.mapValues { (_, msgs) -> msgs.reversed() }
    }

    // messageId → date label (for resolving visible message to its day)
    val messageIdToDate = remember(grouped) {
        grouped.flatMap { (label, msgs) -> msgs.map { it.id to label } }.toMap()
    }

    // date label → index of that day's header item in the LazyColumn.
    // Items are laid out: [messages.reversed()..., header] for each day.
    // With reverseLayout=true, higher indices appear visually higher (older dates).
    val dateToHeaderIndex = remember(grouped) {
        var idx = 0
        buildMap<String, Int> {
            grouped.forEach { (label, messages) ->
                idx += messages.size   // skip message items
                put(label, idx)        // header is at this index
                idx++
            }
        }
    }

    // Date label of the topmost visible item (oldest message currently on screen).
    // With reverseLayout=true, highest item index = visually highest (oldest).
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

    // Stable label for the pill: hold the last non-empty value so the pill
    // never shows blank text during layout-recalculation gaps at day boundaries.
    var pillDateLabel by remember { mutableStateOf("") }
    if (visibleDate.isNotEmpty()) pillDateLabel = visibleDate

    // Scroll to the date header for the given label.
    fun scrollToDateLabel(label: String) {
        dateToHeaderIndex[label]?.let { idx ->
            scope.launch { listState.animateScrollToItem(idx) }
        }
    }

    if (showExportSheet) {
        val selectedMessages = uiState.messages.filter { it.id in uiState.selectedMessageIds }
        ExportBottomSheet(
            messages = selectedMessages,
            threadDisplayName = uiState.thread?.displayName ?: "",
            ownAddress = "",
            onDismiss = { showExportSheet = false }
        )
    }

    if (showCalendarPicker) {
        CalendarPickerDialog(
            activeDates = activeDates,
            onDateSelected = { scrollTo, wasSnapped, tappedLabel ->
                showCalendarPicker = false
                val label = localDateToLabel(scrollTo)
                scrollToDateLabel(label)
                if (wasSnapped) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "No messages on $tappedLabel — jumped to nearest day",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            },
            onDismiss = { showCalendarPicker = false }
        )
    }

    if (uiState.showDefaultSmsDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDefaultSmsDialog() },
            title = { Text("Set Postmark as default SMS app") },
            text = { Text("To send messages, Postmark needs to be your default SMS app.") },
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(uiState.thread?.displayName ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        bottomBar = {
            ReplyBar(
                text = uiState.replyText,
                onTextChange = { viewModel.onReplyTextChanged(it) },
                onSend = { viewModel.sendMessage() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                grouped.forEach { (dateLabel, _) ->
                    val msgs = reversedByDate[dateLabel] ?: emptyList()
                    items(msgs, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            timestampPref = timestampPref,
                            isTimestampExpanded = message.id in uiState.expandedTimestampIds,
                            onToggleTimestamp = { viewModel.toggleTimestamp(message.id) }
                        )
                    }
                    item(key = "header_$dateLabel") {
                        DateHeader(label = dateLabel)
                    }
                }
            }

            FloatingDatePill(
                dateLabel = pillDateLabel,
                visible = pillVisible,
                onClick = { showCalendarPicker = true },
                modifier = Modifier.align(Alignment.TopCenter)
            )
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

// ── CalendarPickerDialog ───────────────────────────────────────────────────────

/**
 * [onDateSelected] is called with (scrollTo, wasSnapped, tappedLabel) where:
 *   scrollTo   — the LocalDate to navigate to (nearest active if tapped day had none)
 *   wasSnapped — true when the navigation target differs from the tapped day
 *   tappedLabel — human-readable label for the day the user tapped (for Snackbar text)
 */
@Composable
private fun CalendarPickerDialog(
    activeDates: Set<LocalDate>,
    onDateSelected: (scrollTo: LocalDate, wasSnapped: Boolean, tappedLabel: String) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultMonth = activeDates.maxOrNull()?.let { YearMonth.from(it) } ?: YearMonth.now()
    var month by remember { mutableStateOf(defaultMonth) }

    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    val tapLabelFormatter = remember { DateTimeFormatter.ofPattern("MMMM d") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                // Month navigation header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Text(
                        text = month.format(monthFormatter),
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next month")
                    }
                }

                // Day-of-week header row (Mon … Sun)
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Calendar grid (6 rows × 7 columns = 42 cells)
                // Offset: Mon=0, Tue=1, … Sun=6
                val firstDay = month.atDay(1)
                val offset = (firstDay.dayOfWeek.value - 1)  // DayOfWeek.MONDAY.value == 1
                val daysInMonth = month.lengthOfMonth()

                (0 until 42).chunked(7).forEach { weekCells ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        weekCells.forEach { cell ->
                            val dayNum = cell - offset + 1
                            val date = if (dayNum in 1..daysInMonth) month.atDay(dayNum) else null
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
                                        if (nearest != null) {
                                            onDateSelected(nearest, true, tappedLabel)
                                        }
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
    val enabled = date != null
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (date != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
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
                    Spacer(Modifier.height(6.dp)) // keep cell height stable
                }
            }
        }
    }
}

// ── Existing composables (unchanged) ──────────────────────────────────────────

@Composable
private fun ReplyBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val counterText = remember(text.length) { smsCounter(text.length) }

    Surface(modifier = modifier, tonalElevation = 3.dp) {
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
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                trailingIcon = counterText?.let { ct ->
                    {
                        Text(
                            text = ct,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (text.length > 160)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
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
                    containerColor = if (text.isNotBlank())
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (text.isNotBlank())
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

private fun smsCounter(length: Int): String? {
    if (length <= 120) return null
    if (length <= 160) return "$length / 160"
    val charsPerPart = 153
    val parts = (length + charsPerPart - 1) / charsPerPart
    val currentPart = (length - 1) / charsPerPart + 1
    return "$currentPart/$parts"
}

@Composable
private fun MessageBubble(
    message: Message,
    timestampPref: TimestampPreference,
    isTimestampExpanded: Boolean,
    onToggleTimestamp: () -> Unit
) {
    val bubbleColor = if (message.isSent)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val alignment = if (message.isSent) Alignment.End else Alignment.Start

    val showTimestamp = when (timestampPref) {
        TimestampPreference.ALWAYS -> true
        TimestampPreference.ON_TAP -> isTimestampExpanded
        TimestampPreference.NEVER  -> false
    }

    val bubbleClick: (() -> Unit)? =
        if (timestampPref == TimestampPreference.ON_TAP) onToggleTimestamp else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (bubbleClick != null) Modifier.clickable { bubbleClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bubbleColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = message.body, style = MaterialTheme.typography.bodyMedium)
        }
        if (showTimestamp) {
            Text(
                text = timeFormatter.format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = if (!message.isSent) 4.dp else 0.dp,
                    end   = if (message.isSent)  4.dp else 0.dp,
                    top   = 2.dp,
                    bottom = 2.dp
                )
            )
        }
        message.reactions.forEach { reaction ->
            Text(
                text = reaction.emoji,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (message.isSent) {
            DeliveryStatusIndicator(
                status = message.deliveryStatus,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

@Composable
private fun DeliveryStatusIndicator(status: Int, modifier: Modifier = Modifier) {
    val (icon, tint) = when (status) {
        DELIVERY_STATUS_PENDING ->
            Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        DELIVERY_STATUS_SENT ->
            Icons.Default.Done to MaterialTheme.colorScheme.onSurfaceVariant
        DELIVERY_STATUS_DELIVERED ->
            Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
        DELIVERY_STATUS_FAILED ->
            Icons.Default.Error to MaterialTheme.colorScheme.error
        else -> return
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.size(12.dp),
        tint = tint
    )
}

@Composable
private fun DateHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
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

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun launchDefaultSmsRoleRequest(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
    } else {
        @Suppress("DEPRECATION")
        context.startActivity(
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(
                Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName
            )
        )
    }
}

private val dayFormatter  = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).also {
    it.timeZone = java.util.TimeZone.getDefault()
}
private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault()).also {
    it.timeZone = java.util.TimeZone.getDefault()
}

/** Converts a [LocalDate] to the same string format used as group keys in [groupByDay]. */
private fun localDateToLabel(date: LocalDate): String =
    dayFormatter.format(Date(date.atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond() * 1000))

/** Start of [dayLabel] in epoch-millis (midnight local time). */
private fun dayStartMs(dayLabel: String): Long =
    dayFormatter.parse(dayLabel)?.time ?: 0L

/** End of [dayLabel] in epoch-millis (23:59:59.999 local time). */
private fun dayEndMs(dayLabel: String): Long =
    (dayFormatter.parse(dayLabel)?.time ?: 0L) + 86_400_000L - 1L

private fun List<Message>.groupByDay(): Map<String, List<Message>> =
    groupBy { dayFormatter.format(Date(it.timestamp)) }
