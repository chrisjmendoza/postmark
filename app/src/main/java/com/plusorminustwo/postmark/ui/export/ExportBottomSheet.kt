package com.plusorminustwo.postmark.ui.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.plusorminustwo.postmark.domain.formatter.ExportFormatter
import com.plusorminustwo.postmark.domain.model.Message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(
    messages: List<Message>,
    threadDisplayName: String,
    ownAddress: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Export ${messages.size} messages",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            FilledTonalButton(
                onClick = {
                    val text = ExportFormatter.formatForCopy(messages, threadDisplayName, ownAddress)
                    copyToClipboard(context, text)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.padding(end = 8.dp))
                Text("Copy (AI / paste)")
            }

            FilledTonalButton(
                onClick = {
                    shareAsImage(context, messages, threadDisplayName, ownAddress)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Image, null, modifier = Modifier.padding(end = 8.dp))
                Text("Share as image")
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Postmark export", text))
}

private fun shareAsImage(
    context: Context,
    messages: List<Message>,
    threadDisplayName: String,
    ownAddress: String
) {
    // Full canvas-to-bitmap rendering implemented in next build;
    // for now share the text transcript via ACTION_SEND
    val text = ExportFormatter.formatForCopy(messages, threadDisplayName, ownAddress)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share conversation"))
}
