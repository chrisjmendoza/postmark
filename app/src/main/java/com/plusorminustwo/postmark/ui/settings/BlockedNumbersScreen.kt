package com.plusorminustwo.postmark.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber

/**
 * Lists the numbers blocked in the system [android.provider.BlockedNumberContract]
 * provider, with a per-row unblock action. Reached from Settings › Privacy.
 *
 * The provider is only accessible while Postmark is the default SMS app; when it
 * isn't, the screen shows an explanation rather than an empty (misleading) list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedNumbersScreen(
    onBack: () -> Unit,
    viewModel: BlockedNumbersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Row pending unblock-confirmation, or null when no dialog is showing.
    var confirmUnblock by remember { mutableStateOf<BlockedNumberRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocked numbers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            !uiState.canBlock -> CenteredMessage(
                padding = padding,
                text = "Postmark must be your default SMS app to view and manage " +
                    "blocked numbers. Set it as default in Settings, or manage blocked " +
                    "numbers from your phone's own settings."
            )

            uiState.loading -> { /* brief; no spinner to avoid a flash on fast loads */ }

            uiState.numbers.isEmpty() -> CenteredMessage(
                padding = padding,
                text = "No blocked numbers.\nNumbers you block from a conversation appear here."
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Scaffold padding carries the status-bar (top, below the app bar) and
                // nav-bar (bottom) insets; as contentPadding the last row scrolls clear
                // of the nav bar instead of hiding behind it.
                contentPadding = padding
            ) {
                items(uiState.numbers, key = { it.id }) { row ->
                    BlockedNumberRowItem(
                        row = row,
                        onUnblock = { confirmUnblock = row }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    confirmUnblock?.let { row ->
        val label = row.contactName ?: formatPhoneNumber(row.number)
        AlertDialog(
            onDismissRequest = { confirmUnblock = null },
            title = { Text("Unblock $label?") },
            text = {
                Text("Calls and texts from this number will be allowed through again.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unblock(row.id)
                    confirmUnblock = null
                }) { Text("Unblock") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnblock = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BlockedNumberRowItem(
    row: BlockedNumberRow,
    onUnblock: () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Contact name (when resolvable) as the primary line, number beneath it;
            // when there's no contact, the formatted number is the only line.
            if (row.contactName != null) {
                Text(row.contactName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatPhoneNumber(row.number),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(formatPhoneNumber(row.number), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onUnblock) { Text("Unblock") }
    }
}

@Composable
private fun CenteredMessage(
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
