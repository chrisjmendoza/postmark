package com.plusorminustwo.postmark.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.formatter.friendlyTimestamp
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.SearchDateRange
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.search.SearchResultGroup
import com.plusorminustwo.postmark.domain.search.matchThreads
import com.plusorminustwo.postmark.ui.components.ContactAvatar
import com.plusorminustwo.postmark.ui.thread.ReactionPills


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onMessageClick: (threadId: Long, messageId: Long) -> Unit,
    onThreadClick: (threadId: Long) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showThreadSheet by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }

    // Contacts whose name/address match the query, shown as a "Conversations" section
    // above the message hits. Suppressed once a thread filter is active — the results
    // are already scoped to one thread, so a contact picker there is redundant.
    val matchedThreads = remember(uiState.query, uiState.threads, uiState.filters.threadId) {
        if (uiState.filters.threadId != null) emptyList()
        else matchThreads(uiState.query, uiState.threads)
    }

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
                                viewModel.setReactionFilter(emoji)
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
                filters            = uiState.filters,
                selectedThread     = uiState.selectedThread,
                sortOrder          = uiState.sortOrder,
                onSortChange       = viewModel::setSortOrder,
                oldestFirst        = uiState.oldestFirst,
                onOldestFirstChange = viewModel::setOldestFirst,
                onFiltersChange    = viewModel::onFiltersChange,
                onDateRangeChange  = viewModel::setDateRangeFilter,
                onThreadChipClick  = { showThreadSheet = true },
                onClearThreadFilter = { viewModel.setThreadFilter(null) },
                onReactionChipClick = { showEmojiSheet = true },
                onClearReactionFilter = { viewModel.setReactionFilter(null) },
                onProtocolFilter    = viewModel::setProtocolFilter
            )

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.results.isEmpty() && matchedThreads.isEmpty() &&
                uiState.query.isBlank() && uiState.filters.isMms == null) {
                // Nothing typed and no protocol filter — show prompt.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text  = "Type to search, or pick SMS / MMS to browse",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (uiState.results.isEmpty() && matchedThreads.isEmpty()) {
                // A real query/filter with zero hits — say so, instead of a blank
                // screen indistinguishable from broken search.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text  = if (uiState.query.isBlank()) "No messages match these filters"
                                else "No results for \"${uiState.query}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (uiState.sortOrder == SortOrder.BY_CONTACT) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    conversationsSection(matchedThreads, onThreadClick)
                    uiState.groupedResults.forEach { group ->
                        stickyHeader(key = "header-${group.threadId}") {
                            SearchGroupHeader(group)
                        }
                        items(group.messages, key = { it.id }) { message ->
                            SearchResultRow(
                                message = message,
                                query = uiState.query,
                                onClick = { onMessageClick(message.threadId, message.id) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    conversationsSection(matchedThreads, onThreadClick)
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
    sortOrder: SortOrder,
    onSortChange: (SortOrder) -> Unit,
    oldestFirst: Boolean,
    onOldestFirstChange: (Boolean) -> Unit,
    onFiltersChange: (SearchFilters) -> Unit,
    onDateRangeChange: (SearchDateRange) -> Unit,
    onThreadChipClick: () -> Unit,
    onClearThreadFilter: () -> Unit,
    onReactionChipClick: () -> Unit,
    onClearReactionFilter: () -> Unit,
    onProtocolFilter: (Boolean?) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            val byContact = sortOrder == SortOrder.BY_CONTACT
            FilterChip(
                selected = byContact,
                onClick  = { onSortChange(if (byContact) SortOrder.MOST_RECENT else SortOrder.BY_CONTACT) },
                label    = { Text("By contact") },
                leadingIcon = {
                    Icon(
                        Icons.Default.SortByAlpha,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            )
        }
        item {
            // Orthogonal sort direction. Unselected = newest first (default). A leading
            // check makes the active state unmistakable, per the owner's preference.
            FilterChip(
                selected = oldestFirst,
                onClick  = { onOldestFirstChange(!oldestFirst) },
                label    = { Text("Oldest first") },
                leadingIcon = if (oldestFirst) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                } else null
            )
        }
        item {
            FilterChip(
                selected = filters.isMms == false,
                onClick  = { onProtocolFilter(if (filters.isMms == false) null else false) },
                label    = { Text("SMS") }
            )
        }
        item {
            FilterChip(
                selected = filters.isMms == true,
                onClick  = { onProtocolFilter(if (filters.isMms == true) null else true) },
                label    = { Text("MMS") }
            )
        }
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
            val rangeSelected = filters.dateRange == SearchDateRange.TODAY
            FilterChip(
                selected = rangeSelected,
                onClick = { onDateRangeChange(if (rangeSelected) SearchDateRange.ALL_TIME else SearchDateRange.TODAY) },
                label = { Text("Today") }
            )
        }
        item {
            val rangeSelected = filters.dateRange == SearchDateRange.LAST_7_DAYS
            FilterChip(
                selected = rangeSelected,
                onClick = { onDateRangeChange(if (rangeSelected) SearchDateRange.ALL_TIME else SearchDateRange.LAST_7_DAYS) },
                label = { Text("7 days") }
            )
        }
        item {
            val rangeSelected = filters.dateRange == SearchDateRange.LAST_30_DAYS
            FilterChip(
                selected = rangeSelected,
                onClick = { onDateRangeChange(if (rangeSelected) SearchDateRange.ALL_TIME else SearchDateRange.LAST_30_DAYS) },
                label = { Text("30 days") }
            )
        }
        item {
            val reactionActive = filters.reactionEmoji != null
            FilterChip(
                selected = reactionActive,
                onClick = {
                    if (reactionActive) onClearReactionFilter() else onReactionChipClick()
                },
                label = { Text(filters.reactionEmoji ?: "Reaction") },
                trailingIcon = if (reactionActive) {
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



/**
 * Prepends the "Conversations" section — a labelled header plus one tappable contact
 * row per matched thread — to the results list. A no-op when [threads] is empty, so it
 * silently disappears for a blank query or when a thread filter already scopes results.
 */
private fun LazyListScope.conversationsSection(
    threads: List<Thread>,
    onThreadClick: (Long) -> Unit
) {
    if (threads.isEmpty()) return
    item(key = "conversations-header") {
        Text(
            text = "Conversations",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
    items(threads, key = { "conv-${it.id}" }) { thread ->
        ConversationResultRow(thread, onThreadClick)
        HorizontalDivider()
    }
}

@Composable
private fun ConversationResultRow(thread: Thread, onClick: (Long) -> Unit) {
    ListItem(
        headlineContent = { Text(thread.nickname ?: thread.displayName) },
        supportingContent = { Text(formatPhoneNumber(thread.address)) },
        leadingContent = {
            ContactAvatar(
                address = thread.address,
                name = thread.nickname ?: thread.displayName,
                colorSeed = thread.address,
                overrideColor = thread.accentColorArgb?.let { Color(it) }
            )
        },
        modifier = Modifier.clickable { onClick(thread.id) }
    )
}

@Composable
private fun SearchGroupHeader(group: SearchResultGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ContactAvatar(
            address = group.thread?.address ?: group.displayName,
            name = group.displayName,
            overrideColor = group.thread?.accentColorArgb?.let { Color(it) },
            size = 28.dp
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = group.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Count of this thread's results under the current filters — muted, so it
            // reads as a subtle tally rather than a second title.
            Text(
                text = "· ${group.messages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SearchResultRow(message: Message, query: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // Remembered: highlightQuery compiles a Regex and rebuilds an
                // AnnotatedString — per visible row per keystroke without the cache.
                text = remember(message.body, query) { highlightQuery(message.body, query) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )
            // Display-only pills below the snippet; inert callback since search rows
            // aren't interactive. FlowRow inside ReactionPills keeps row height in check.
            if (message.reactions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ReactionPills(
                    reactions = message.reactions,
                    onReactionClick = {}
                )
            }
        }
        // Recency-aware timestamp, same visual language as the conversation list's
        // ThreadRow (labelSmall / onSurfaceVariant). `now` is captured at composition;
        // the list recomposes on data changes, so a briefly-stale "5m" is fine.
        val context = LocalContext.current
        val label = remember(message.timestamp) {
            friendlyTimestamp(
                timestampMs = message.timestamp,
                nowMs = System.currentTimeMillis(),
                is24Hour = android.text.format.DateFormat.is24HourFormat(context)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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

