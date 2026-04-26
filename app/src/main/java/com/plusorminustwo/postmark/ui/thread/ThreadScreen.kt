package com.plusorminustwo.postmark.ui.thread

import android.content.Intent
import android.os.Build
import android.app.role.RoleManager
import android.provider.Telephony
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_DELIVERED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
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
    val context = LocalContext.current

    if (showExportSheet) {
        val selectedMessages = uiState.messages.filter { it.id in uiState.selectedMessageIds }
        ExportBottomSheet(
            messages = selectedMessages,
            threadDisplayName = uiState.thread?.displayName ?: "",
            ownAddress = "",
            onDismiss = { showExportSheet = false }
        )
    }

    if (uiState.showDefaultSmsDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDefaultSmsDialog() },
            title = { Text("Set Postmark as default SMS app") },
            text = { Text("To send messages, Postmark needs to be your default SMS app.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissDefaultSmsDialog()
                    launchDefaultSmsRoleRequest(context)
                }) { Text("Set as default") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDefaultSmsDialog() }) { Text("Not now") }
            }
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
        },
        bottomBar = {
            if (!uiState.isSelectionMode) {
                ReplyBar(
                    text = uiState.replyText,
                    onTextChange = { viewModel.onReplyTextChanged(it) },
                    onSend = { viewModel.sendMessage() }
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
private fun ReplyBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val counterText = remember(text.length) { smsCounter(text.length) }

    Surface(modifier = modifier, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                trailingIcon = counterText?.let { ct ->
                    {
                        Text(
                            text = ct,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (text.length > 160)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (text.isNotBlank())
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (text.isNotBlank())
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

private fun smsCounter(length: Int): String? {
    if (length <= 120) return null
    if (length <= 160) return "$length / 160"
    val charsPerPart = 153
    val parts = (length + charsPerPart - 1) / charsPerPart
    val currentPart = (length - 1) / charsPerPart + 1
    return "$currentPart/$parts"
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
        if (message.isSent) {
            DeliveryStatusIndicator(
                status = message.deliveryStatus,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

@Composable
private fun DeliveryStatusIndicator(status: Int, modifier: Modifier = Modifier) {
    val (icon, tint) = when (status) {
        DELIVERY_STATUS_PENDING ->
            Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        DELIVERY_STATUS_SENT ->
            Icons.Default.Done to MaterialTheme.colorScheme.onSurfaceVariant
        DELIVERY_STATUS_DELIVERED ->
            Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
        DELIVERY_STATUS_FAILED ->
            Icons.Default.Error to MaterialTheme.colorScheme.error
        else -> return
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.size(12.dp),
        tint = tint
    )
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

private fun launchDefaultSmsRoleRequest(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
    } else {
        @Suppress("DEPRECATION")
        context.startActivity(
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(
                Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName
            )
        )
    }
}

private val dayFormatter = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())

private fun List<Message>.groupByDay(): Map<String, List<Message>> =
    groupBy { dayFormatter.format(Date(it.timestamp)) }
