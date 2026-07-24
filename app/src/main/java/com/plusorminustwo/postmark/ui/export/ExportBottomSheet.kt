package com.plusorminustwo.postmark.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet offering the ways to export a message selection: copy as plain text, or
 * render it to a shareable image. Reappears (restored July 2026) now that Canvas→PNG
 * rendering exists behind [onShareAsImage].
 *
 * While an image render is in flight [isExporting] disables both buttons and shows a
 * spinner on the "Share as image" row — the work runs off the main thread in the caller.
 * navigationBarsPadding keeps the buttons clear of the gesture/nav bar (house rule).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(
    messageCount: Int,
    isExporting: Boolean,
    onCopy: () -> Unit,
    onShareAsImage: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!isExporting) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Export $messageCount ${if (messageCount == 1) "message" else "messages"}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            FilledTonalButton(
                onClick = onCopy,
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.padding(end = 8.dp))
                Text("Copy as text")
            }

            FilledTonalButton(
                onClick = onShareAsImage,
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Image, null, modifier = Modifier.padding(end = 8.dp))
                }
                Text(if (isExporting) "Rendering…" else "Share as image")
            }
        }
    }
}
