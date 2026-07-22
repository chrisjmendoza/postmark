package com.plusorminustwo.postmark.ui.settings.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.ui.components.ContactAvatar
import com.plusorminustwo.postmark.ui.components.DateRangeBottomSheet
import com.plusorminustwo.postmark.ui.settings.RestoreStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Selective export: pick conversations (searchable, multi-select), an optional
 * date range, a format — readable text + media (default) or a restorable v2
 * backup archive — then a destination file via the system CreateDocument dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    viewModel: ExportViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    val visibleThreads by viewModel.visibleThreads.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val format by viewModel.format.collectAsState()
    val dateRange by viewModel.dateRange.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()
    val feedback by viewModel.feedback.collectAsState()

    var showDateSheet by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(feedback) {
        feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { viewModel.startExport(it) } }

    if (showDateSheet) {
        DateRangeBottomSheet(
            onSelect = { start, end ->
                viewModel.setDateRange(start, end)
                showDateSheet = false
            },
            onDismiss = { showDateSheet = false }
        )
    }

    val exportBusy = exportStatus is RestoreStatus.Running || exportStatus is RestoreStatus.Queued

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text("Search conversations") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                // navigationBarsPadding keeps the button above the 3-button/gesture nav bar —
                // Scaffold does not inset custom bottomBar content on its own.
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    ExportStatusRow(exportStatus)
                    Button(
                        onClick = { createDocument.launch(viewModel.suggestedFileName()) },
                        enabled = selectedIds.isNotEmpty() && !exportBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (selectedIds.isEmpty()) "Export"
                            else "Export ${selectedIds.size} conversation" +
                                if (selectedIds.size == 1) "" else "s"
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Format choice
            FormatOptionRow(
                selected = format == ExportFormat.READABLE,
                title = "Readable text + media",
                subtitle = "One text file per conversation, photos and videos as regular files. Opens anywhere; can't be restored into Postmark.",
                onClick = { viewModel.setFormat(ExportFormat.READABLE) }
            )
            FormatOptionRow(
                selected = format == ExportFormat.BACKUP,
                title = "Postmark backup",
                subtitle = "Machine format — restores into Postmark and merges without duplicates.",
                onClick = { viewModel.setFormat(ExportFormat.BACKUP) }
            )
            HorizontalDivider()

            // Date range row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.DateRange,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    dateRange?.let { (start, end) ->
                        "${start.formatted()} – ${end.formatted()}"
                    } ?: "All time",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (dateRange != null) {
                    IconButton(onClick = { viewModel.clearDateRange() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear date range")
                    }
                }
                TextButton(onClick = { showDateSheet = true }) {
                    Text(if (dateRange == null) "Pick range" else "Change")
                }
            }

            // Select-all row (operates on the visible/filtered list)
            val allVisibleSelected = visibleThreads.isNotEmpty() &&
                visibleThreads.all { it.id in selectedIds }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setAllVisible(!allVisibleSelected) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (query.isBlank()) "Select all (${visibleThreads.size})"
                    else "Select all matches (${visibleThreads.size})",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Checkbox(
                    checked = allVisibleSelected,
                    onCheckedChange = { viewModel.setAllVisible(it) }
                )
            }
            HorizontalDivider()

            if (visibleThreads.isEmpty()) {
                Text(
                    if (query.isBlank()) "No conversations yet"
                    else "No conversations match \"$query\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(visibleThreads, key = { it.id }) { thread ->
                        val name = thread.nickname ?: thread.displayName
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text(formatPhoneNumber(thread.address)) },
                            leadingContent = {
                                ContactAvatar(
                                    address = thread.address,
                                    name = name,
                                    colorSeed = thread.address,
                                    overrideColor = thread.accentColorArgb?.let { Color(it) }
                                )
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = thread.id in selectedIds,
                                    onCheckedChange = { viewModel.toggleThread(thread.id) }
                                )
                            },
                            modifier = Modifier.clickable { viewModel.toggleThread(thread.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatOptionRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExportStatusRow(status: RestoreStatus) {
    when (status) {
        is RestoreStatus.None -> return
        is RestoreStatus.Queued -> Text(
            "Export queued…",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        is RestoreStatus.Running -> Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            val label = if (status.total > 0)
                "${status.phase} — ${"%,d".format(status.done)} / ${"%,d".format(status.total)}"
            else status.phase
            Text(label, style = MaterialTheme.typography.bodySmall)
            if (status.total > 0) {
                LinearProgressIndicator(
                    progress = { status.done.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is RestoreStatus.Finished -> Text(
            status.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.success) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

private val EXPORT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun LocalDate.formatted(): String = format(EXPORT_DATE_FORMATTER)
