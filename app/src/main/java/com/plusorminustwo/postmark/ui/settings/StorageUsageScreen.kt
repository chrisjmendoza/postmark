package com.plusorminustwo.postmark.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.domain.storage.ConversationStorageRow

/**
 * Settings › Storage usage — where Postmark's on-disk bytes go, section by section,
 * plus a per-conversation breakdown and two safe cleanup actions. Reached from
 * Settings' General section.
 *
 * Received MMS media lives in the OS's own content provider (content://mms/part), not
 * in app storage, so it's never counted here — the footer says so explicitly, otherwise
 * the numbers on this screen would look implausibly small against a large MMS history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageUsageScreen(
    onBack: () -> Unit,
    viewModel: StorageUsageViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbar by viewModel.snackbar.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbar) {
        snackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    fun size(bytes: Long) = Formatter.formatFileSize(context, bytes)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage usage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !uiState.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.loading && uiState.conversations.isEmpty() && uiState.databaseBytes == 0L) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding
        ) {
            item {
                StorageSectionHeader("Breakdown")
                StorageRow("Database", size(uiState.databaseBytes), "Messages, threads, reactions")
                HorizontalDivider()
                StorageRow(
                    "Attachments & voice memos",
                    size(uiState.attachmentBytes),
                    "${uiState.attachmentCount} file${if (uiState.attachmentCount == 1) "" else "s"} — outgoing media you've sent"
                )
                HorizontalDivider()
                StorageRow("Chat backgrounds", size(uiState.chatBackgroundBytes), "Custom images set per-chat or app-wide")
                HorizontalDivider()
                StorageRow("Image cache", size(uiState.imageCacheBytes), "Thumbnails cached for faster loading")
                HorizontalDivider()
                val backupSubtitle = buildString {
                    append("On this device")
                    if (uiState.safBackupBytes != null) {
                        append(", plus ")
                        append(size(uiState.safBackupBytes!!))
                        append(" in \"")
                        append(uiState.safBackupLabel)
                        append("\"")
                    }
                }
                StorageRow(
                    "Backups",
                    size(uiState.backupBytes + (uiState.safBackupBytes ?: 0L)),
                    backupSubtitle
                )
                HorizontalDivider()
                StorageRow("Sync log", size(uiState.syncLogBytes), "Diagnostic event log")
                HorizontalDivider()
            }

            item {
                StorageSectionHeader("Clean up")
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::cleanUpUnusedFiles,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clean up unused files") }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Removes leftover attachment cache no message references. " +
                            "Never touches attachments a sent message still points at, or a " +
                            "voice memo you haven't sent yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = viewModel::clearImageCache,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear image cache") }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Frees thumbnail cache. Images simply reload next time you view them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
            }

            if (uiState.conversations.isNotEmpty()) {
                item { StorageSectionHeader("Top conversations") }
                items(uiState.conversations, key = { it.threadId }) { row ->
                    ConversationStorageRowItem(row, ::size)
                    HorizontalDivider()
                }
            }

            item {
                Text(
                    "Received photos and videos are stored by Android itself, not by " +
                        "Postmark, so they aren't counted above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun StorageSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun StorageRow(title: String, size: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(size, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConversationStorageRowItem(row: ConversationStorageRow, size: (Long) -> String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${row.messageCount} message${if (row.messageCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (row.attachmentBytes > 0) {
            Text(
                size(row.attachmentBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
