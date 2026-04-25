package com.plusorminustwo.postmark.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.ui.export.ExportBottomSheet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    threadId: Long,
    onBack: () -> Unit,
    viewModel: ThreadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var showExportSheet by remember { mutableStateOf(false) }

    if (showExportSheet) {
        val selectedMessages = uiState.messages.filter { it.id in uiState.selectedMessageIds }
        ExportBottomSheet(
            messages = selectedMessages,
            threadDisplayName = uiState.thread?.displayName ?: "",
            ownAddress = "",
            onDismiss = { showExportSheet = false }
        )
    }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = uiState.selectedMessageIds.size,
                    onClose = { viewModel.exitSelectionMode() },
                    onCopy = { showExportSheet = true },
                    onShare = { showExportSheet = true }
                )
            } else {
                TopAppBar(
                    title = { Text(uiState.thread?.displayName ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewModel.enterSelectionMode() }) {
                            Text("Select")
                        }
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            val grouped = uiState.messages.groupByDay()
            grouped.forEach { (dateLabel, messages) ->
                items(messages.reversed(), key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        isSelected = message.id in uiState.selectedMessageIds,
                        isSelectionMode = uiState.isSelectionMode,
                        onToggleSelect = { viewModel.toggleSelection(message.id) }
                    )
                }
                item(key = "header_$dateLabel") {
                    DateHeader(
                        label = dateLabel,
                        onSelectDay = { /* viewModel.selectDay(...) */ }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onToggleSelect: () -> Unit
) {
    val bubbleColor = if (message.isSent)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val alignment = if (message.isSent) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isSelectionMode) Modifier.clickable { onToggleSelect() } else Modifier)
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = message.body, style = MaterialTheme.typography.bodyMedium)
        }
        message.reactions.forEach { reaction ->
            Text(
                text = reaction.emoji,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun DateHeader(label: String, onSelectDay: () -> Unit) {
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
        TextButton(onClick = onSelectDay) {
            Text("Select day", style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, "Copy")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, "Share")
            }
        }
    )
}

private val dayFormatter = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())

private fun List<Message>.groupByDay(): Map<String, List<Message>> =
    groupBy { dayFormatter.format(Date(it.timestamp)) }
