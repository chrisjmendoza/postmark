package com.plusorminustwo.postmark.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.model.Message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onMessageClick: (threadId: Long, messageId: Long) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showThreadSheet by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }

    if (showEmojiSheet) {
        ModalBottomSheet(onDismissRequest = { showEmojiSheet = false }) {
            Text(
                "Filter by reaction",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (uiState.reactionEmojis.isEmpty()) {
                Text(
                    "No reactions yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                LazyColumn {
                    items(uiState.reactionEmojis) { emoji ->
                        ListItem(
                            headlineContent = { Text(emoji) },
                            modifier = Modifier.clickable {
                                viewModel.onFiltersChange(uiState.filters.copy(hasReaction = true))
                                showEmojiSheet = false
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showThreadSheet) {
        ModalBottomSheet(onDismissRequest = { showThreadSheet = false }) {
            Text(
                "Filter by thread",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn {
                items(uiState.threads, key = { it.id }) { thread ->
                    ListItem(
                        headlineContent = { Text(thread.displayName) },
                        supportingContent = { Text(formatPhoneNumber(thread.address)) },
                        modifier = Modifier.clickable {
                            viewModel.setThreadFilter(thread)
                            showThreadSheet = false
                        }
                    )
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = uiState.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text("Search messages…") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterChips(
                filters = uiState.filters,
                selectedThread = uiState.selectedThread,
                onFiltersChange = viewModel::onFiltersChange,
                onThreadChipClick = { showThreadSheet = true },
                onClearThreadFilter = { viewModel.setThreadFilter(null) },
                onReactionChipClick = { showEmojiSheet = true }
            )

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.results, key = { it.id }) { message ->
                        SearchResultRow(
                            message = message,
                            query = uiState.query,
                            onClick = { onMessageClick(message.threadId, message.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChips(
    filters: SearchFilters,
    selectedThread: com.plusorminustwo.postmark.domain.model.Thread?,
    onFiltersChange: (SearchFilters) -> Unit,
    onThreadChipClick: () -> Unit,
    onClearThreadFilter: () -> Unit,
    onReactionChipClick: () -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = filters.isSentOnly == true,
                onClick = {
                    onFiltersChange(filters.copy(isSentOnly = if (filters.isSentOnly == true) null else true))
                },
                label = { Text("Sent") }
            )
        }
        item {
            FilterChip(
                selected = filters.isSentOnly == false,
                onClick = {
                    onFiltersChange(filters.copy(isSentOnly = if (filters.isSentOnly == false) null else false))
                },
                label = { Text("Received") }
            )
        }
        item {
            FilterChip(
                selected = filters.hasReaction,
                onClick = {
                    if (filters.hasReaction) onFiltersChange(filters.copy(hasReaction = false))
                    else onReactionChipClick()
                },
                label = { Text("Reactions") },
                trailingIcon = if (filters.hasReaction) {
                    { Icon(Icons.Default.Clear, contentDescription = "Clear reaction filter",
                        modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else null
            )
        }
        item {
            FilterChip(
                selected = selectedThread != null,
                onClick = {
                    if (selectedThread != null) onClearThreadFilter()
                    else onThreadChipClick()
                },
                label = { Text(selectedThread?.displayName ?: "Thread") },
                trailingIcon = if (selectedThread != null) {
                    { Icon(Icons.Default.Clear, contentDescription = "Clear thread filter",
                        modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else null
            )
        }
    }
}

@Composable
private fun SearchResultRow(message: Message, query: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = highlightQuery(message.body, query),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3
        )
    }
}

private fun highlightQuery(text: String, query: String) = buildAnnotatedString {
    if (query.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val pattern = Regex("\\b${Regex.escape(query)}", RegexOption.IGNORE_CASE)
    var last = 0
    pattern.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(match.value)
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

