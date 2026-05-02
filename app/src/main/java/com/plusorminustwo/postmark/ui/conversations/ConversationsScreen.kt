package com.plusorminustwo.postmark.ui.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.ui.components.LetterAvatar
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onThreadClick: (Long) -> Unit,
    onSearchClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val threads by viewModel.threads.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val isDefaultSmsApp by viewModel.isDefaultSmsApp.collectAsState()
    val roleBannerDismissed by viewModel.roleBannerDismissed.collectAsState()
    val threadList = threads  // local val so Kotlin can smart-cast the nullable

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Postmark") },
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
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Role denial banner — shown when the app is not the default SMS app
            // and the user hasn't dismissed it this install.
            if (!isDefaultSmsApp && !roleBannerDismissed) {
                RoleDenialBanner(onDismiss = viewModel::dismissRoleBanner)
            }
            when {
                threadList == null -> {
                    // Room hasn't emitted yet — show nothing to avoid empty-state flash.
                    Box(Modifier.fillMaxSize())
                }
                threadList.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isSyncing) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator()
                                Text("Syncing messages…", style = MaterialTheme.typography.bodyLarge)
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("No conversations yet", style = MaterialTheme.typography.bodyLarge)
                                Button(onClick = { viewModel.triggerSync() }) {
                                    Text("Sync messages")
                                }
                                OutlinedButton(onClick = { viewModel.loadSampleData() }) {
                                    Text("Load sample data")
                                }
                                syncStatus?.let {
                                    Text(
                                        text = "Last sync: $it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 32.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(threadList, key = { it.id }) { thread ->
                            ThreadRow(
                                thread = thread,
                                onClick = { onThreadClick(thread.id) },
                                onTogglePin = { viewModel.togglePin(thread.id, thread.isPinned) },
                                onToggleMute = { viewModel.toggleMute(thread.id, thread.isMuted) }
                            )
                            HorizontalDivider()
                        }
                    }
                    syncStatus?.let {
                        Text(
                            text = "Last sync: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Role denial banner ────────────────────────────────────────────────────────
/** Persistent amber banner explaining read-only limitations when the app is not
 *  the default SMS app. Dismissed via the × button; state persists across launches. */
@Composable
private fun RoleDenialBanner(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Postmark isn’t your default SMS app — some messages may be missing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
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

/** A single conversation row. Long-press opens a context menu with Pin/Unpin and Mute/Unmute. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    thread: Thread,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
) {
    // Controls visibility of the long-press dropdown menu.
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        LetterAvatar(name = thread.displayName, colorSeed = thread.address)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatPhoneNumber(thread.displayName),
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
        Text(
            text = formatDate(thread.lastMessageAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        } // end Row

        // ── Context menu (long-press) ───────────────────────────────────────
        // Anchored to the Row so it appears near the long-pressed item.
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (thread.isPinned) "Unpin" else "Pin") },
                onClick = { menuExpanded = false; onTogglePin() }
            )
            DropdownMenuItem(
                text = { Text(if (thread.isMuted) "Unmute" else "Mute") },
                onClick = { menuExpanded = false; onToggleMute() }
            )
        }
    } // end Box
}

private fun formatDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 24 * 60 * 60 * 1000 ->
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
        diff < 7 * 24 * 60 * 60 * 1000 ->
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else ->
            SimpleDateFormat("MM/dd/yy", Locale.getDefault()).format(Date(timestamp))
    }
}
