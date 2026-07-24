package com.plusorminustwo.postmark.ui.conversations

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds
import com.plusorminustwo.postmark.ui.components.ContactAvatar
import com.plusorminustwo.postmark.ui.theme.isAppInDarkTheme
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.selection.SwipeAction
import com.plusorminustwo.postmark.domain.selection.SwipeDirection
import com.plusorminustwo.postmark.domain.selection.bulkToggleTarget
import com.plusorminustwo.postmark.domain.selection.resolveSwipeAction
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.formatter.friendlyTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onThreadClick: (Long) -> Unit,
    onSearchClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNewConversationClick: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val threads by viewModel.threads.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val isDefaultSmsApp by viewModel.isDefaultSmsApp.collectAsState()
    val roleBannerDismissed by viewModel.roleBannerDismissed.collectAsState()
    // One-time long-press-to-multi-select hint (un-dismissed); further gated on a
    // non-empty, non-selecting list below via shouldShowMultiSelectHint.
    val multiSelectHintDismissed by viewModel.multiSelectHintDismissed.collectAsState()
    // Live unread-message counts keyed by threadId — drives the badge in ThreadRow.
    val unreadCounts by viewModel.unreadCounts.collectAsState()
    // Whether the "unread only" filter chip is active.
    val showUnreadOnly by viewModel.showUnreadOnly.collectAsState()
    // Total unread thread count — shown inside the filter chip so the user can see at a glance
    // how many conversations are waiting even before activating the filter.
    val unreadThreadCount = remember(unreadCounts) { unreadThreadCount(unreadCounts) }
    val threadList = threads  // local val so Kotlin can smart-cast the nullable

    // ── Multi-select ───────────────────────────────────────────────────────────
    // Set of selected thread ids; a non-empty set means selection mode is active.
    val selectedIds by viewModel.selectedThreadIds.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()
    // The currently-listed threads that are selected — drives the selection bar's
    // apply-to-all Pin/Mute labels. Selection can only include visible rows, so this
    // resolves to exactly the selected threads.
    val selectedThreads = remember(threadList, selectedIds) {
        threadList?.filter { it.id in selectedIds } ?: emptyList()
    }

    // One-shot delete-result messages surfaced through the Scaffold Snackbar.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { snackbarHostState.showSnackbar(it) }
    }
    // Confirmation dialog visibility for the destructive bulk delete.
    var showDeleteDialog by remember { mutableStateOf(false) }
    // Confirmation dialog visibility for the bulk "Report spam" action.
    var showBulkSpamDialog by remember { mutableStateOf(false) }
    // Confirmation dialog visibility for the bulk "Block" action. Only opened when Postmark
    // is already the default SMS app — see pendingDefaultSmsGateReason below otherwise.
    var showBulkBlockDialog by remember { mutableStateOf(false) }
    // Non-null while the swipe-left confirm dialog is up for that thread id (see
    // SwipeableThreadRow's onSwipeDelete). Mirrors the bulk-delete dialog above but for a
    // single conversation — same delete path, same confirm-gate, one fewer thing to select.
    var pendingSwipeDeleteId by remember { mutableStateOf<Long?>(null) }
    // Reason text for the shared "Set Postmark as default SMS app" gate dialog below;
    // non-null while showing. Shared by swipe-to-delete and the bulk Block action — both
    // need the same fix before they can write to a system provider, and neither can be
    // gated by simply hiding a button once the action has already been triggered (a swipe
    // already happened; Block was tapped from the overflow menu). One dialog, one mechanism.
    var pendingDefaultSmsGateReason by remember { mutableStateOf<String?>(null) }

    // Back exits selection mode rather than leaving the screen.
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    // Re-check whether we hold the default SMS role every time this screen resumes
    // (e.g. after returning from the system default-apps settings screen).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshDefaultSmsStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // RoleManager.createRequestRoleIntent MUST be launched via startActivityForResult;
    // a plain startActivity() is silently ignored on API 29+.
    val roleRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshDefaultSmsStatus()
    }
    // Shared by the RoleDenialBanner tap target and the swipe-delete "set default" dialog
    // below — one place that knows how to launch the system default-SMS-app prompt.
    val screenContext = LocalContext.current
    val launchRoleRequest: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = screenContext.getSystemService(RoleManager::class.java)
            roleRequestLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else {
            @Suppress("DEPRECATION")
            screenContext.startActivity(
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(
                    Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, screenContext.packageName
                )
            )
        }
    }

    // ── Home-screen background ────────────────────────────────────────────────
    // Same id vocabulary as the chat background: a built-in gradient resolves to a Brush,
    // a custom "image:" id resolves to a File that Coil draws. Both are remembered keyed on
    // the id (+ theme) so they're built once per change, never per frame. When nothing is
    // set BOTH are null, `hasBackground` is false, and the screen renders exactly as it did
    // before this existed — the Scaffold keeps its own container color and opaque top bar.
    val homeBackgroundId by viewModel.homeBackgroundId.collectAsState()
    val isDarkTheme = isAppInDarkTheme()
    val homeBackgroundImageFile = remember(homeBackgroundId) {
        ChatBackgrounds.resolveImageFile(homeBackgroundId, viewModel::homeBackgroundImageFile)
    }
    val homeBackground = ChatBackgrounds.resolve(homeBackgroundId)
    val homeBackgroundBrush = remember(homeBackground.id, isDarkTheme) {
        if (homeBackground == ChatBackgrounds.None) null
        else Brush.verticalGradient(
            (if (isDarkTheme) homeBackground.darkColorsArgb else homeBackground.lightColorsArgb)
                .map { Color(it) }
        )
    }
    val hasBackground = homeBackgroundBrush != null || homeBackgroundImageFile != null

    // Painted BEHIND the Scaffold rather than inside its content slot, so the background
    // runs edge-to-edge under the status bar and the top app bar like a wallpaper; inside
    // the content slot it would start below the top bar and read as a panel.
    Box(modifier = Modifier.fillMaxSize()) {
        homeBackgroundBrush?.let { brush ->
            Box(Modifier.matchParentSize().background(brush))
        }
        if (homeBackgroundImageFile != null) {
            val ctx = LocalContext.current
            AsyncImage(
                model = remember(homeBackgroundImageFile) {
                    ImageRequest.Builder(ctx).data(homeBackgroundImageFile).crossfade(true).build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            // Same theme-aware legibility scrim ThreadScreen uses over a photo background:
            // thread rows are plain text on a transparent surface, so without it a busy
            // photo makes names and previews unreadable.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        if (isDarkTheme) Color.Black.copy(alpha = 0.4f)
                        else Color.White.copy(alpha = 0.4f)
                    )
            )
        }

        Scaffold(
            // Transparent only while a background is set, so the un-customized screen keeps
            // Scaffold's own default container color rather than inheriting whatever sits behind.
            containerColor = if (hasBackground) Color.Transparent else MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // Selection replaces the normal top bar while active (mirrors ThreadScreen).
                if (selectionMode) {
                    ConversationSelectionTopBar(
                        selectedCount = selectedIds.size,
                        // Same apply-to-all decision the ViewModel action makes, so the
                        // menu label always matches what tapping it will do.
                        pinAll = bulkToggleTarget(selectedThreads.map { it.isPinned }),
                        muteAll = bulkToggleTarget(selectedThreads.map { it.isMuted }),
                        showDelete = isDefaultSmsApp,
                        onClose = { viewModel.clearSelection() },
                        onMarkRead = { viewModel.markSelectedRead() },
                        onMarkUnread = { viewModel.markSelectedUnread() },
                        onTogglePin = { viewModel.pinSelected() },
                        onToggleMute = { viewModel.muteSelected() },
                        onReportSpam = { showBulkSpamDialog = true },
                        onBlock = {
                            if (isDefaultSmsApp) showBulkBlockDialog = true
                            else pendingDefaultSmsGateReason =
                                "To block numbers, Postmark needs to be your default SMS app."
                        },
                        onDelete = { showDeleteDialog = true }
                    )
                } else {
                    TopAppBar(
                        title = { Text("Postmark") },
                        // Let the background run behind the bar too — an opaque bar over a photo
                        // reads as a band across the top rather than one continuous surface.
                        colors = if (hasBackground) {
                            TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                        } else {
                            TopAppBarDefaults.topAppBarColors()
                        },
                        actions = {
                            IconButton(onClick = onSearchClick) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = onStatsClick) {
                                Icon(Icons.Default.BarChart, contentDescription = "Stats")
                            }
                            IconButton(onClick = onSettingsClick) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    )
                }
            },
            // FAB: compose a new message to any contact or number. Hidden during selection.
            floatingActionButton = {
                if (!selectionMode) {
                    FloatingActionButton(onClick = onNewConversationClick) {
                        Icon(Icons.Default.Edit, contentDescription = "New message")
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // ── Unread filter chip row ─────────────────────────────────────────
                // Only shown when there are unread threads so the bar doesn't appear
                // in an all-read inbox where it would just be visual noise.
                AnimatedVisibility(
                    visible = (unreadThreadCount > 0 || showUnreadOnly) && !selectionMode,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = showUnreadOnly,
                            onClick  = { viewModel.toggleUnreadFilter() },
                            label    = {
                                Text(
                                    if (unreadThreadCount > 0) "Unread ($unreadThreadCount)"
                                    else "Unread",
                                    maxLines = 1
                                )
                            },
                            leadingIcon = if (showUnreadOnly) {
                                { Icon(Icons.Default.Close, contentDescription = "Clear filter", modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
                // Role denial banner — shown when the app is not the default SMS app
                // and the user hasn't dismissed it this install.
                if (!isDefaultSmsApp && !roleBannerDismissed && !selectionMode) {
                    RoleDenialBanner(
                        onDismiss = viewModel::dismissRoleBanner,
                        onSetDefault = launchRoleRequest
                    )
                }
                // Slim one-time hint teaching the long-press multi-select gesture. Sits below
                // the filter/role bars and above the list; hidden once dismissed, on an empty
                // list, or while selection is already active.
                if (shouldShowMultiSelectHint(multiSelectHintDismissed, threadList?.size ?: 0, selectionMode)) {
                    MultiSelectHintRow(onDismiss = viewModel::dismissMultiSelectHint)
                }
                when {
                    threadList == null -> {
                        // Room hasn't emitted yet — show nothing to avoid empty-state flash.
                        Box(Modifier.fillMaxSize())
                    }
                    threadList.isEmpty() && showUnreadOnly -> {
                        // Filter is active but everything is read.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No unread messages", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    threadList.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (isSyncing) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                ) {
                                    Text("Syncing messages…", style = MaterialTheme.typography.bodyLarge)
                                    SyncProgressBanner(syncProgress)
                                }
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("No conversations yet", style = MaterialTheme.typography.bodyLarge)
                                    Button(onClick = { viewModel.triggerSync() }) {
                                        Text("Sync messages")
                                    }
                                    syncStatus?.let {
                                        SyncStatusBar(it)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        // Progress banner below the top bar while a sync is in flight.
                        if (isSyncing) {
                            SyncProgressBanner(syncProgress)
                        }
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(threadList, key = { it.id }) { thread ->
                                // animateItem: pin/unpin and new-message reordering slide rows
                                // to their new position instead of teleporting them.
                                Column(modifier = Modifier.animateItem()) {
                                    if (selectionMode) {
                                        // Swipe is disabled while selecting — a swiped-away row
                                        // fighting the selection tap target would be confusing,
                                        // and the selection top bar already covers these actions.
                                        ThreadRow(
                                            thread = thread,
                                            unreadCount = unreadCounts[thread.id] ?: 0,
                                            selectionMode = true,
                                            selected = thread.id in selectedIds,
                                            onClick = { onThreadClick(thread.id) },
                                            onToggleSelect = { viewModel.toggleThreadSelection(thread.id) },
                                            onLongPress = { viewModel.enterSelection(thread.id) }
                                        )
                                    } else {
                                        SwipeableThreadRow(
                                            thread = thread,
                                            unreadCount = unreadCounts[thread.id] ?: 0,
                                            onClick = { onThreadClick(thread.id) },
                                            onLongPress = { viewModel.enterSelection(thread.id) },
                                            onSwipeDeleteRequested = { threadId ->
                                                if (isDefaultSmsApp) pendingSwipeDeleteId = threadId
                                                else pendingDefaultSmsGateReason =
                                                    "To delete conversations, Postmark needs to be your default SMS app."
                                            },
                                            onSwipeToggleRead = { threadId, markRead ->
                                                viewModel.toggleThreadRead(threadId, markRead)
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                        syncStatus?.let {
                            SyncStatusBar(it, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // ── Delete confirmation ─────────────────────────────────────────────────
            // The one permitted destructive path: deletes the selected conversations from
            // the phone's SMS/MMS providers. Only reachable when Postmark is the default
            // SMS app (the selection bar hides Delete otherwise).
            if (showDeleteDialog) {
                val count = selectedIds.size
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = {
                        Text(if (count == 1) "Delete 1 conversation?" else "Delete $count conversations?")
                    },
                    text = {
                        Text(
                            "This removes " +
                                (if (count == 1) "it" else "them") +
                                " and all their messages from this phone. This can’t be undone."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteDialog = false
                            viewModel.deleteSelected()
                        }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // ── Bulk "Report spam" confirmation ──────────────────────────────────────
            // Mirrors ThreadScreen's single-thread spam confirm (showSpamConfirmDialog there):
            // marking spam hides the thread away, so it's confirmed first. Always one-way from
            // here — spam threads are already excluded from this list (ThreadDao.observeNonSpam),
            // so the selection can never contain one already spam to restore.
            if (showBulkSpamDialog) {
                val count = selectedIds.size
                AlertDialog(
                    onDismissRequest = { showBulkSpamDialog = false },
                    title = {
                        Text(if (count == 1) "Report 1 conversation as spam?" else "Report $count conversations as spam?")
                    },
                    text = {
                        Text(
                            (if (count == 1) "It" else "They") +
                                " will be moved to the Spam folder — hidden from your list and " +
                                "silenced (no notifications). You can restore " +
                                (if (count == 1) "it" else "them") +
                                " anytime from Settings › Privacy › Spam."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showBulkSpamDialog = false
                            viewModel.reportSpamSelected()
                        }) { Text("Report") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBulkSpamDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // ── Bulk "Block" confirmation ────────────────────────────────────────────
            // Mirrors ThreadScreen's single-thread Block confirm. Only reachable when
            // Postmark is the default SMS app (gated above via pendingDefaultSmsGateReason).
            // Group threads in the selection are skipped by the ViewModel — no single number
            // to block — and that outcome is reported in the result snackbar, not here; this
            // dialog's count is the full selection, same as the bulk-delete dialog above.
            if (showBulkBlockDialog) {
                val count = selectedIds.size
                AlertDialog(
                    onDismissRequest = { showBulkBlockDialog = false },
                    title = { Text(if (count == 1) "Block 1 number?" else "Block $count numbers?") },
                    text = {
                        Text(
                            "Calls and texts from " + (if (count == 1) "it" else "them") +
                                " will be rejected by your phone. You can unblock " +
                                (if (count == 1) "it" else "them") +
                                " later from Settings › Privacy › Blocked numbers."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showBulkBlockDialog = false
                            viewModel.blockSelected()
                        }) { Text("Block") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBulkBlockDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // ── Swipe-to-delete confirmation ─────────────────────────────────────────
            // Same real, irreversible delete as above (viewModel.deleteThread reuses the
            // exact deleteThreadsInternal path deleteSelected uses) — a swipe only ever
            // snaps back and opens this dialog, it never deletes on its own.
            pendingSwipeDeleteId?.let { threadId ->
                AlertDialog(
                    onDismissRequest = { pendingSwipeDeleteId = null },
                    title = { Text("Delete conversation?") },
                    text = {
                        Text("This removes it and all its messages from this phone. This can’t be undone.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingSwipeDeleteId = null
                            viewModel.deleteThread(threadId)
                        }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingSwipeDeleteId = null }) { Text("Cancel") }
                    }
                )
            }

            // ── "Set Postmark as default SMS app" gate ───────────────────────────────
            // Shared by swipe-to-delete and the bulk Block action (mirrors ThreadScreen's
            // "Set Postmark as default SMS app" dialog) — see pendingDefaultSmsGateReason
            // above for why one dialog covers both triggers.
            pendingDefaultSmsGateReason?.let { reason ->
                AlertDialog(
                    onDismissRequest = { pendingDefaultSmsGateReason = null },
                    title = { Text("Set Postmark as default SMS app") },
                    text = { Text(reason) },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingDefaultSmsGateReason = null
                            launchRoleRequest()
                        }) { Text("Set as default") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDefaultSmsGateReason = null }) { Text("Not now") }
                    }
                )
            }
        }
    }
}

// ── Sync status bar ───────────────────────────────────────────────────────────
/** Shows a subtle one-line status for success/info states, or a red warning
 *  banner with a warning icon for error states (status starts with "Error:").
 *  Only rendered when [status] is non-null. */
@Composable
private fun SyncStatusBar(status: String, modifier: Modifier = Modifier) {
    val isError = status.startsWith("Error:")
    if (isError) {
        // ── Error: prominent red banner so the user can't miss it ────────────
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Sync error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        // ── Success / info: quiet one-liner ──────────────────────────────────
        Text(
            text = "Last sync: $status",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

// ── Sync progress banner ──────────────────────────────────────────────────────
/** Fills the full width with a determinate (or indeterminate) progress bar and
 *  a text line showing the current phase, row counts, and ETA.
 *  Shown both above the thread list (while threads already exist) and in the
 *  center of the empty-state screen (first launch). */
@Composable
private fun SyncProgressBanner(progress: SyncProgress?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (progress != null && progress.total > 0) {
                // Determinate — we know how many rows there are.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${progress.phase} \u2014 ${ "%,d".format(progress.done)} / ${"%,d".format(progress.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (progress.eta.isNotEmpty()) {
                        Text(
                            text = progress.eta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { progress.done.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // Indeterminate — early in sync or SMS phase (no total known).
                val label = progress?.phase?.takeIf { it.isNotEmpty() } ?: "Syncing\u2026"
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ── Role denial banner ────────────────────────────────────────────────────────
/** Persistent amber banner explaining read-only limitations when the app is not
 *  the default SMS app. Tap the text to launch the system set-default dialog;
 *  dismiss via the × button. State persists across launches. */
@Composable
private fun RoleDenialBanner(onDismiss: () -> Unit, onSetDefault: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Postmark isn\u2019t your default SMS app \u2014 tap to fix.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSetDefault)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Multi-select discovery hint ─────────────────────────────────────────────────
/** Slim one-time hint teaching the (otherwise invisible) long-press-to-multi-select
 *  gesture. A surfaceVariant container keeps it informational rather than shouting; the ×
 *  ([IconButton], 48dp touch target) dismisses it for good. colorScheme roles only, so it
 *  stays legible in light and dark. */
@Composable
private fun MultiSelectHintRow(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tip: long-press a conversation to select several at once",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss tip",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Swipe actions ─────────────────────────────────────────────────────────────
/** Wraps [ThreadRow] in a Material 3 [SwipeToDismissBox] for the standard swipe gestures:
 *  swipe left (EndToStart) reveals a red Delete affordance, swipe right (StartToEnd)
 *  reveals a read/unread toggle. Neither swipe actually dismisses the row —
 *  `confirmValueChange` always returns `false`, so a completed swipe fires its action
 *  once and then springs straight back to Settled. [resolveSwipeAction] is the pure
 *  decision of what a completed swipe does; this composable only wires it to callbacks.
 *  Only mounted when the row is NOT in selection mode — see the call site. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableThreadRow(
    thread: Thread,
    unreadCount: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeDeleteRequested: (Long) -> Unit,
    onSwipeToggleRead: (Long, Boolean) -> Unit,
) {
    val isRead = unreadCount == 0
    val dismissState = rememberSwipeToDismissBoxState(
        // Never let the box actually settle into a dismissed state — every completed
        // swipe just fires its action (delete confirm / read toggle) and snaps back.
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.EndToStart ->
                    if (resolveSwipeAction(SwipeDirection.EndToStart, isRead) == SwipeAction.Delete) {
                        onSwipeDeleteRequested(thread.id)
                    }
                SwipeToDismissBoxValue.StartToEnd ->
                    when (resolveSwipeAction(SwipeDirection.StartToEnd, isRead)) {
                        SwipeAction.MarkRead -> onSwipeToggleRead(thread.id, true)
                        SwipeAction.MarkUnread -> onSwipeToggleRead(thread.id, false)
                        SwipeAction.Delete -> Unit
                    }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        // Roughly 40% of the row's width — clears accidental micro-swipes (list scroll,
        // stray touches) without requiring a near-full-width drag.
        positionalThreshold = { totalDistance -> totalDistance * 0.4f }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeActionBackground(dismissState.dismissDirection, isRead) }
    ) {
        ThreadRow(
            thread = thread,
            unreadCount = unreadCount,
            selectionMode = false,
            selected = false,
            onClick = onClick,
            onToggleSelect = {},
            onLongPress = onLongPress
        )
    }
}

/** Full-bleed color + icon revealed behind a conversation row as it's swiped. colorScheme
 *  roles only (errorContainer for delete, primaryContainer for the read toggle) so both
 *  light and dark theme stay legible. The read-toggle icon reflects what the swipe is
 *  about to do — mirrors the current state rather than a static icon. */
@Composable
private fun SwipeActionBackground(direction: SwipeToDismissBoxValue, isRead: Boolean) {
    val (color, contentColor, icon, alignment) = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> SwipeBackgroundSpec(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            icon = Icons.Default.Delete,
            alignment = Alignment.CenterEnd
        )
        SwipeToDismissBoxValue.StartToEnd -> SwipeBackgroundSpec(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = if (isRead) Icons.Default.MarkEmailUnread else Icons.Default.MarkEmailRead,
            alignment = Alignment.CenterStart
        )
        SwipeToDismissBoxValue.Settled -> return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = contentColor)
    }
}

private data class SwipeBackgroundSpec(
    val color: Color,
    val contentColor: Color,
    val icon: ImageVector,
    val alignment: Alignment
)

/** A single conversation row. In normal mode a tap opens the thread and a long-press
 *  enters multi-select with this row selected. While selection mode is active a tap
 *  toggles this row instead of navigating. Selected rows tint their container and swap
 *  the avatar for a check so the selection is unmistakable. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    thread: Thread,
    unreadCount: Int,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onClick() },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar area doubles as the selection indicator: a filled check circle replaces
        // the contact avatar while selected.
        if (selected) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        } else {
            ContactAvatar(
                address = thread.address,
                name = thread.nickname ?: thread.displayName,
                overrideColor = thread.accentColorArgb?.let { Color(it) }
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.nickname ?: formatPhoneNumber(thread.displayName),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (thread.lastMessagePreview.isNotEmpty()) {
                Text(
                    text = thread.lastMessagePreview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // Recency-appropriate label ("just now", "5m", "9:41 AM", "Mon", "Apr 25").
        // `now` is captured when the row (re)composes for this timestamp; the list
        // recomposes on data changes, so labels stay as fresh as the screen without a
        // ticking clock — a briefly-stale "5m" is fine. remember-keyed on the timestamp
        // and the 12/24h preference so the formatters are built once per row, not per frame.
        val context = LocalContext.current
        val label = remember(thread.lastMessageAt) {
            friendlyTimestamp(
                timestampMs = thread.lastMessageAt,
                nowMs = System.currentTimeMillis(),
                is24Hour = android.text.format.DateFormat.is24HourFormat(context)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Unread-message badge — shown when there is at least one unread message.
        if (unreadCount > 0) {
            Badge { Text(unreadCount.coerceAtMost(99).toString()) }
        }
        if (thread.isPinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = "Pinned",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (thread.isMuted) {
            Icon(
                imageVector = Icons.Default.NotificationsOff,
                contentDescription = "Muted",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Selection top bar ─────────────────────────────────────────────────────────
/** Replaces the normal top bar while conversations are selected (mirrors ThreadScreen's
 *  SELECTION mode). Surfaces Mark-read and Delete as icons and the stateful Pin/Mute plus
 *  Mark-unread/Report spam/Block actions in an overflow menu (Pin/Mute labels reflect the
 *  apply-to-all decision). Delete is only offered when Postmark is the default SMS app;
 *  Block is always offered but the caller gates what tapping it does (confirm dialog vs.
 *  the "set default" prompt) since group threads in the selection are simply skipped rather
 *  than hiding the whole action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSelectionTopBar(
    selectedCount: Int,
    pinAll: Boolean,
    muteAll: Boolean,
    showDelete: Boolean,
    onClose: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onReportSpam: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("$selectedCount selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onMarkRead) {
                Icon(Icons.Default.DoneAll, contentDescription = "Mark read")
            }
            if (showDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
            IconButton(onClick = { overflowExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More actions")
            }
            DropdownMenu(
                expanded = overflowExpanded,
                onDismissRequest = { overflowExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (pinAll) "Pin all" else "Unpin all") },
                    onClick = { overflowExpanded = false; onTogglePin() }
                )
                DropdownMenuItem(
                    text = { Text(if (muteAll) "Mute all" else "Unmute all") },
                    onClick = { overflowExpanded = false; onToggleMute() }
                )
                DropdownMenuItem(
                    text = { Text("Mark unread") },
                    onClick = { overflowExpanded = false; onMarkUnread() }
                )
                DropdownMenuItem(
                    text = { Text("Report spam") },
                    onClick = { overflowExpanded = false; onReportSpam() }
                )
                DropdownMenuItem(
                    text = { Text("Block") },
                    onClick = { overflowExpanded = false; onBlock() }
                )
            }
        }
    )
}
