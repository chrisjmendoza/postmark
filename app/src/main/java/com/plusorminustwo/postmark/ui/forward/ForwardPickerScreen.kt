package com.plusorminustwo.postmark.ui.forward

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.ui.components.ContactAvatar

/**
 * "Forward to..." destination picker — reached from a message's action bar / the
 * full-screen image viewer's overflow menu. Shows recent conversations by default;
 * typing filters to a live contact search (same source as [com.plusorminustwo.postmark.ui.conversations.NewConversationScreen]).
 * Selecting either immediately sends a copy of the source message there.
 *
 * @param onForwarded Called with the destination threadId once the forward is sent —
 *                     the caller navigates there, replacing this picker on the back stack.
 * @param onBack      Called when the user taps the back arrow without picking anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardPickerScreen(
    onForwarded: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: ForwardPickerViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val recentThreads by viewModel.recentThreads.collectAsState()
    val forwardedToThreadId by viewModel.forwardedToThreadId.collectAsState()

    LaunchedEffect(forwardedToThreadId) {
        forwardedToThreadId?.let {
            viewModel.consumeForwardResult()
            onForwarded(it)
        }
    }

    val focusRequester = remember { FocusRequester() }

    val isDialableNumber = remember(query) {
        query.replace("[\\s\\-().+]".toRegex(), "")
            .let { stripped -> stripped.length >= 7 && stripped.all { it.isDigit() } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text("Forward to name or number") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = if (isDialableNumber) ImeAction.Go else ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { if (isDialableNumber) viewModel.forwardToAddress(query) },
                            onSearch = { if (isDialableNumber) viewModel.forwardToAddress(query) }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                actions = {
                    if (isDialableNumber) {
                        IconButton(onClick = { viewModel.forwardToAddress(query) }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Forward")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (query.length < 2) {
                items(recentThreads, key = { "thread_${it.id}" }) { thread ->
                    ListItem(
                        headlineContent = { Text(thread.nickname ?: thread.displayName) },
                        supportingContent = { Text(formatPhoneNumber(thread.address)) },
                        leadingContent = {
                            ContactAvatar(
                                address = thread.address,
                                name = thread.nickname ?: thread.displayName,
                                colorSeed = thread.address
                            )
                        },
                        modifier = Modifier.clickable { viewModel.forwardToThread(thread) }
                    )
                    HorizontalDivider()
                }
            } else {
                items(contacts, key = { "contact_${it.address}" }) { contact ->
                    ListItem(
                        headlineContent = { Text(contact.displayName) },
                        supportingContent = { Text(formatPhoneNumber(contact.address)) },
                        leadingContent = {
                            ContactAvatar(
                                address = contact.address,
                                name = contact.displayName,
                                colorSeed = contact.address
                            )
                        },
                        modifier = Modifier.clickable { viewModel.forwardToAddress(contact.address) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
