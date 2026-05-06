package com.plusorminustwo.postmark.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevOptionsScreen(
    onBack: () -> Unit,
    viewModel: DevOptionsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val feedback by viewModel.feedback.collectAsState()
    val isRecomputing by viewModel.isRecomputing.collectAsState()
    val isReprocessing by viewModel.isReprocessing.collectAsState()
    val logContent by viewModel.logContent.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer options") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DevSectionHeader("Stats")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Recalculate stats", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Recompute all conversation statistics",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isRecomputing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = viewModel::recomputeStats) {
                        Icon(Icons.Default.Refresh, "Recalculate")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            DevSectionHeader("Sample data")
            DevButton("Load sample data", "Insert 5 threads + ~70 messages") {
                viewModel.loadSampleData()
            }
            DevButton("Clear sample data", "Remove only the 5 seeded sample threads") {
                viewModel.clearSampleData()
            }
            DevButton("Clear all data", "Delete all threads, messages, and reactions") {
                viewModel.clearAllData()
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            DevSectionHeader("SMS sync")
            DevButton("Reset sync flag", "Allow next trigger to re-run the full sync") {
                viewModel.resetSyncFlag()
            }
            DevButton("Trigger sync now", "Reset flag and enqueue sync worker immediately") {
                viewModel.triggerSync()
            }
            DevButton(
                "Wipe DB + re-import",
                "Delete all local data then re-import everything from system SMS (safe — never deletes from system)"
            ) {
                viewModel.wipeAndResync()
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            DevSectionHeader("Reactions (debug)")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Reprocess reactions", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Scan all messages, convert reaction fallbacks to Reaction entities, remove fallback bubbles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isReprocessing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = viewModel::reprocessReactions) {
                        Icon(Icons.Default.Refresh, "Reprocess reactions")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Sync log ──────────────────────────────────────────────────────
            // Displays the persistent append-only log written by SyncLogger.
            // Useful for diagnosing SMS loss, worker cancellations, and other
            // events that happened in the background and are otherwise invisible.
            DevSectionHeader("Sync log")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::refreshLog,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Load")
                }
                // Share fires the system share sheet (Gmail, Drive, Messages, etc.).
                // FileProvider serves the log file as a content:// URI so no storage
                // permission is required and the receiving app can't browse app storage.
                OutlinedButton(
                    onClick = {
                        val logFile = File(context.filesDir, "sync_log.txt")
                        if (!logFile.exists()) {
                            // Nothing to share yet — load first so the user gets feedback.
                            viewModel.refreshLog()
                            return@OutlinedButton
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            logFile
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Postmark sync log")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, "Share sync log")
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Share log",
                        modifier = Modifier.size(16.dp).padding(end = 0.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Share")
                }
                OutlinedButton(
                    onClick = viewModel::clearSyncLog,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear")
                }
            }
            logContent?.let { content ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 320.dp)
                ) {
                    // Inner scroll so the log is independently scrollable while the
                    // outer Column scroll still works for the rest of the screen.
                    val logScrollState = rememberScrollState()
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .verticalScroll(logScrollState)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DevSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun DevButton(title: String, subtitle: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
