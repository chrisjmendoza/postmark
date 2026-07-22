package com.plusorminustwo.postmark.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.ui.components.ContactAvatar

/**
 * The Spam folder — lists threads the user has reported as spam, hidden out of the main
 * conversation list. Reached from Settings › Privacy › Spam.
 *
 * Tapping a row opens the normal thread screen (the same route as the main list); the
 * per-row "Not spam" button restores the thread. The auto-flag heuristics and the inline
 * notification "report spam" action are intentionally deferred (see docs/TODO.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamScreen(
    onThreadClick: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: SpamViewModel = hiltViewModel()
) {
    val threads by viewModel.spamThreads.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spam") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val list = threads
        when {
            list == null -> { /* brief load; no spinner to avoid a flash on fast loads */ }

            list.isEmpty() -> CenteredSpamMessage(
                padding = padding,
                text = "No spam.\nConversations you report as spam appear here."
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Scaffold padding carries the status-bar (top, below the app bar) and
                // nav-bar (bottom) insets; as contentPadding the last row scrolls clear
                // of the nav bar instead of hiding behind it.
                contentPadding = padding
            ) {
                items(list, key = { it.id }) { thread ->
                    SpamThreadRow(
                        thread = thread,
                        onClick = { onThreadClick(thread.id) },
                        onNotSpam = { viewModel.notSpam(thread.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SpamThreadRow(
    thread: Thread,
    onClick: () -> Unit,
    onNotSpam: () -> Unit
) {
    // Match the conversation list: avatar seeds off the raw name, the label formats the number.
    val avatarName = thread.nickname ?: thread.displayName
    val label = thread.nickname ?: formatPhoneNumber(thread.displayName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            address = thread.address,
            name = avatarName,
            overrideColor = thread.accentColorArgb?.let { Color(it) }
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (thread.lastMessagePreview.isNotBlank()) {
                Text(
                    thread.lastMessagePreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onNotSpam) { Text("Not spam") }
    }
}

@Composable
private fun CenteredSpamMessage(
    padding: androidx.compose.foundation.layout.PaddingValues,
    text: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
