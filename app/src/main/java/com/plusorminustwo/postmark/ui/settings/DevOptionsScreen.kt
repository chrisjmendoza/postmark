package com.plusorminustwo.postmark.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevOptionsScreen(
    onBack: () -> Unit,
    viewModel: DevOptionsViewModel = hiltViewModel()
) {
    val feedback by viewModel.feedback.collectAsState()
    val isRecomputing by viewModel.isRecomputing.collectAsState()
    val isReprocessing by viewModel.isReprocessing.collectAsState()
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
