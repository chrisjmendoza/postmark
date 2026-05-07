package com.plusorminustwo.postmark.ui.conversations

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
 * Screen for composing a new message to a contact or raw phone number.
 *
 * Shows a live-filtered contact list as the user types in the recipient field.
 * Selecting a contact (or typing a valid number and tapping the send icon)
 * triggers [onNavigateToThread] with the resolved threadId.
 *
 * @param onNavigateToThread Called with the threadId once the thread is ready.
 * @param onBack             Called when the user taps the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(
    onNavigateToThread: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: NewConversationViewModel = hiltViewModel()
) {
    val query    by viewModel.query.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val navigateToThread by viewModel.navigateToThread.collectAsState()

    // ── Navigation trigger ────────────────────────────────────────────────────
    // Once the ViewModel signals a threadId, consume it and navigate.
    LaunchedEffect(navigateToThread) {
        navigateToThread?.let {
            viewModel.consumeNavigation()
            onNavigateToThread(it)
        }
    }

    // ── Auto-focus the recipient field on first composition ───────────────────
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // ── Phone number validation ───────────────────────────────────────────────
    // True when the raw query looks like a dialable number (≥7 digits after
    // stripping formatting characters). Shows the compose/send action button.
    val isDialableNumber = remember(query) {
        query.replace("[\\s\\-().+]".toRegex(), "")
            .let { stripped -> stripped.length >= 7 && stripped.all { it.isDigit() } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // ── Navigation icon: back arrow ───────────────────────────────
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // ── Title: the recipient text field ───────────────────────────
                // Placed in the title slot so it fills the available toolbar width.
                title = {
                    OutlinedTextField(
                        value          = query,
                        onValueChange  = viewModel::onQueryChange,
                        placeholder    = { Text("Name or phone number") },
                        singleLine     = true,
                        modifier       = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(
                            // Text keyboard so names can be typed; numeric input still works.
                            keyboardType = KeyboardType.Text,
                            // "Go" key composes immediately when a dialable number is typed.
                            imeAction = if (isDialableNumber) ImeAction.Go else ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onGo     = { if (isDialableNumber) viewModel.startConversation(query) },
                            onSearch = { if (isDialableNumber) viewModel.startConversation(query) }
                        ),
                        // Remove the visible border so the field blends into the top bar.
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                // ── Action: send/compose button ───────────────────────────────
                // Only visible once the query looks like a valid phone number.
                actions = {
                    if (isDialableNumber) {
                        IconButton(onClick = { viewModel.startConversation(query) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Start conversation"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        // ── Contact results list ──────────────────────────────────────────────
        // imePadding() ensures the list shrinks above the software keyboard so
        // results at the bottom of the list are never hidden behind it.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            items(contacts, key = { it.address }) { contact ->
                ListItem(
                    headlineContent   = { Text(contact.displayName) },
                    supportingContent = { Text(formatPhoneNumber(contact.address)) },
                    // Contact photo or letter avatar, matching the conversations list style.
                    leadingContent    = {
                        ContactAvatar(
                            address   = contact.address,
                            name      = contact.displayName,
                            colorSeed = contact.address
                        )
                    },
                    modifier = Modifier.clickable { viewModel.startConversation(contact.address) }
                )
                HorizontalDivider()
            }
        }
    }
}
