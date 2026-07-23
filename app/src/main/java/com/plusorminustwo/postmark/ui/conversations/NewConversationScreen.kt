package com.plusorminustwo.postmark.ui.conversations

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.data.contacts.formatContactSupportingText
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.model.RecipientChip
import com.plusorminustwo.postmark.domain.model.normalizeAddressForDedupe
import com.plusorminustwo.postmark.ui.components.ContactAvatar

/**
 * Screen for composing a new message to a contact or raw phone number, or for
 * originating a new group conversation (GROUP_MESSAGING_SPEC §3).
 *
 * Shows a live-filtered contact list as the user types in the recipient field.
 * Tapping a contact (or typing a valid number and hitting Go/send) starts a 1:1
 * conversation immediately — exactly one tap, unchanged from before group support.
 *
 * Group building has a visible entry point — a pinned "Start group conversation" row
 * at the top of the list — as well as a long-press-on-a-contact power-user shortcut;
 * per standing project feedback a feature must never be gesture-discoverable only.
 * Either one turns on [NewConversationViewModel.selectionMode], which shows the chip
 * strip (even empty) and switches every row/field to additive behavior: taps toggle
 * contacts in and out of the strip, manual numbers are added as chips instead of sent,
 * and a confirm action in the top bar creates the group and navigates to it. The top
 * bar's close icon or the system back button exits the mode and discards the strip.
 *
 * @param onNavigateToThread Called with the threadId once the thread is ready.
 * @param onBack             Called when the user taps the back arrow (selection mode off).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NewConversationScreen(
    onNavigateToThread: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: NewConversationViewModel = hiltViewModel()
) {
    val query    by viewModel.query.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val navigateToThread by viewModel.navigateToThread.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedRecipients by viewModel.selectedRecipients.collectAsState()

    // ── Navigation trigger ────────────────────────────────────────────────────
    // Once the ViewModel signals a threadId, consume it and navigate.
    LaunchedEffect(navigateToThread) {
        navigateToThread?.let {
            viewModel.consumeNavigation()
            onNavigateToThread(it)
        }
    }

    // System back exits selection mode (discarding the strip) instead of leaving the
    // screen — mirrors ConversationsScreen's multi-select BackHandler.
    BackHandler(enabled = selectionMode) { viewModel.exitSelectionMode() }

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
                // ── Navigation icon: back arrow, or close while selecting ─────
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { viewModel.exitSelectionMode() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel group")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
                            onGo     = { if (isDialableNumber) viewModel.onManualEntrySubmitted(query) },
                            onSearch = { if (isDialableNumber) viewModel.onManualEntrySubmitted(query) }
                        ),
                        // Remove the visible border so the field blends into the top bar.
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                // ── Actions: manual-entry send + group confirm ────────────────
                // Send is only visible once the query looks like a valid phone number —
                // identical condition/handler to before group support when selection
                // mode is off (onManualEntrySubmitted falls through to startConversation).
                // The confirm check only appears once selecting AND at least one
                // recipient is staged (an empty strip has nothing to confirm).
                actions = {
                    if (isDialableNumber) {
                        IconButton(onClick = { viewModel.onManualEntrySubmitted(query) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Start conversation"
                            )
                        }
                    }
                    if (selectionMode && selectedRecipients.isNotEmpty()) {
                        IconButton(onClick = { viewModel.confirmSelection() }) {
                            Icon(Icons.Filled.Check, contentDescription = "Create group")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Staged recipient chips ─────────────────────────────────────────
            // Shown for the whole time selection mode is on, even with zero chips —
            // an empty strip is still visible proof the mode is active (standing
            // feedback: a mode must never be invisible/silent).
            AnimatedVisibility(
                visible = selectionMode,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    RecipientChipStrip(
                        recipients = selectedRecipients,
                        onRemove   = viewModel::removeChip
                    )
                    HorizontalDivider()
                }
            }
            // ── Contact results list ──────────────────────────────────────────
            // imePadding() ensures the list shrinks above the software keyboard so
            // results at the bottom of the list are never hidden behind it.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
            ) {
                // ── Visible entry point: pinned "Start group conversation" row ─────
                // Hidden once already selecting (nothing to enter). This is the
                // discoverable counterpart to the long-press shortcut below — per
                // standing feedback, a feature can never be gesture-only.
                if (!selectionMode) {
                    item(key = "start_group") {
                        ListItem(
                            headlineContent = {
                                Text("Start group conversation", fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = { Text("Message multiple people at once") },
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.GroupAdd,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            },
                            modifier = Modifier.clickable { viewModel.enterSelectionMode() }
                        )
                        HorizontalDivider()
                    }
                }
                items(contacts, key = { it.address }) { contact ->
                    val isSelected = selectedRecipients.any {
                        normalizeAddressForDedupe(it.address) == normalizeAddressForDedupe(contact.address)
                    }
                    val haptics = LocalHapticFeedback.current
                    ListItem(
                        headlineContent   = { Text(contact.displayName) },
                        supportingContent = {
                            Text(formatContactSupportingText(contact.label, formatPhoneNumber(contact.address)))
                        },
                        // Contact photo/letter avatar, or a filled check while selected —
                        // mirrors ConversationsScreen's ThreadRow selection treatment.
                        leadingContent    = {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            } else {
                                ContactAvatar(
                                    address   = contact.address,
                                    name      = contact.displayName,
                                    colorSeed = contact.address
                                )
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                // Selection mode off: exactly today's single-tap behavior.
                                // Selection mode on: tapping toggles this contact instead.
                                if (!selectionMode) {
                                    viewModel.startConversation(contact.address)
                                } else {
                                    viewModel.toggleContact(contact)
                                }
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleContact(contact)
                            }
                        )
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Horizontally scrollable strip of staged group recipients. Tapping a chip removes it —
 *  the close icon is a visual affordance, not a separate hit target, so there's no nested
 *  clickable to fight the chip's own click handling. Shown even with zero chips (selection
 *  mode is on but nobody's picked yet) with a placeholder label, so the strip itself is
 *  always the visible proof that selection mode is active. */
@Composable
private fun RecipientChipStrip(
    recipients: List<RecipientChip>,
    onRemove: (String) -> Unit
) {
    if (recipients.isEmpty()) {
        Text(
            text = "Add people to your group",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
        return
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(recipients, key = { it.address }) { chip ->
            InputChip(
                selected = false,
                onClick  = { onRemove(chip.address) },
                label    = { Text(chip.displayName) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove ${chip.displayName}",
                        modifier = Modifier.size(InputChipDefaults.IconSize)
                    )
                }
            )
        }
    }
}
