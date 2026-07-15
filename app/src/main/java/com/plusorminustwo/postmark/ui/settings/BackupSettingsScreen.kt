package com.plusorminustwo.postmark.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.service.backup.BackupFrequency

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    onExportClick: () -> Unit = {},
    viewModel: BackupSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE) }

    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", true)) }
    var requireWifi by remember { mutableStateOf(prefs.getBoolean("require_wifi", true)) }
    var requireCharging by remember { mutableStateOf(prefs.getBoolean("require_charging", true)) }
    var frequency by remember {
        mutableStateOf(
            BackupFrequency.valueOf(prefs.getString("frequency", BackupFrequency.DAILY.name)!!)
        )
    }
    var retentionCount by remember { mutableIntStateOf(prefs.getInt("retention_count", 5)) }

    val backupFiles by viewModel.backupFiles.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    val restoreStatus by viewModel.restoreStatus.collectAsState()
    val pendingRestore by viewModel.pendingRestore.collectAsState()
    val backupFolder by viewModel.backupFolder.collectAsState()
    val feedback by viewModel.feedback.collectAsState()

    var fileToDelete by remember { mutableStateOf<String?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(feedback) {
        feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    // Refresh the file list whenever a backup or restore finishes running.
    LaunchedEffect(backupStatus, restoreStatus) {
        viewModel.refreshBackupFiles()
    }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.prepareRestoreFromUri(it) } }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.setBackupFolder(it) } }

    // ── Confirmation dialogs ──────────────────────────────────────────────────

    fileToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete backup") },
            text = { Text("Delete \"$name\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBackupFile(name)
                    fileToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("Delete all backups") },
            text = { Text("Delete all backup files? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllBackupFiles()
                    showDeleteAllConfirm = false
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) { Text("Cancel") }
            }
        )
    }

    pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingRestore() },
            title = { Text("Restore backup") },
            text = {
                val manifest = pending.manifest
                Text(
                    buildString {
                        if (manifest != null) {
                            append("\"${pending.displayName}\" from ")
                            append(formatBackupDate(manifest.exportedAt))
                            append(" contains ${manifest.threadCount} conversations and ")
                            append("${manifest.messageCount} messages.")
                        } else {
                            append("\"${pending.displayName}\" is an older Postmark backup ")
                            append("(text messages only).")
                        }
                        append("\n\nRestoring adds messages and conversations that are ")
                        append("missing from this phone. Nothing is deleted or overwritten, ")
                        append("and messages you already have are skipped.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.startRestore() }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingRestore() }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Enable toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Automatic backups", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge)
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean("enabled", it).apply()
                    viewModel.applySchedule()
                })
            }

            if (enabled) {
                // Frequency
                Text("Frequency", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow {
                    BackupFrequency.entries.forEachIndexed { index, freq ->
                        SegmentedButton(
                            selected = frequency == freq,
                            onClick = {
                                frequency = freq
                                prefs.edit().putString("frequency", freq.name).apply()
                                viewModel.applySchedule()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, BackupFrequency.entries.size),
                            label = { Text(freq.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                // Constraints
                Text("Constraints", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wi-Fi only", modifier = Modifier.weight(1f))
                    Switch(checked = requireWifi, onCheckedChange = {
                        requireWifi = it
                        prefs.edit().putBoolean("require_wifi", it).apply()
                        viewModel.applySchedule()
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Charging only", modifier = Modifier.weight(1f))
                    Switch(checked = requireCharging, onCheckedChange = {
                        requireCharging = it
                        prefs.edit().putBoolean("require_charging", it).apply()
                        viewModel.applySchedule()
                    })
                }

                // Retention
                Text("Keep last $retentionCount backups", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = retentionCount.toFloat(),
                    onValueChange = { retentionCount = it.toInt() },
                    onValueChangeFinished = {
                        prefs.edit().putInt("retention_count", retentionCount).apply()
                    },
                    valueRange = 1f..30f,
                    steps = 28
                )
            }

            HorizontalDivider()

            // ── WorkManager status chip ───────────────────────────────────────
            BackupStatusRow(backupStatus)

            // Manual backup
            Button(
                onClick = { viewModel.runNow() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back up now")
            }

            // ── Backup location ───────────────────────────────────────────────
            Text("Backup folder", style = MaterialTheme.typography.labelLarge)
            if (backupFolder != null) {
                Text(
                    backupFolder ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (viewModel.folderFallbackHappened()) {
                    Text(
                        "The last backup couldn't use this folder and was saved to " +
                            "app storage instead. Re-choose the folder to fix access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Text(
                    "App storage — erased if Postmark is uninstalled. Choose a folder " +
                        "to keep backups through reinstalls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderPicker.launch(null) }) {
                    Text(if (backupFolder != null) "Change folder" else "Choose folder")
                }
                if (backupFolder != null) {
                    TextButton(onClick = { viewModel.clearBackupFolder() }) {
                        Text("Use app storage")
                    }
                }
            }

            HorizontalDivider()

            // ── Restore ───────────────────────────────────────────────────────
            Text("Restore", style = MaterialTheme.typography.titleSmall)
            RestoreStatusRow(restoreStatus)
            OutlinedButton(
                onClick = { restorePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = restoreStatus !is RestoreStatus.Running &&
                    restoreStatus !is RestoreStatus.Queued
            ) {
                Text("Restore from backup file…")
            }

            HorizontalDivider()

            // ── Export ────────────────────────────────────────────────────────
            Text("Export", style = MaterialTheme.typography.titleSmall)
            Text(
                "Save chosen conversations — optionally a date range — as a " +
                    "backup file you can keep anywhere or restore later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onExportClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export conversations…")
            }

            HorizontalDivider()

            // ── Backup history ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Backup history",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall)
                if (backupFiles.isNotEmpty()) {
                    TextButton(onClick = { showDeleteAllConfirm = true }) {
                        Text("Delete all")
                    }
                }
            }

            if (backupFiles.isEmpty()) {
                Text("No backups yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    backupFiles.forEach { file ->
                        BackupFileRow(
                            file = file,
                            onRestoreClick = { viewModel.prepareRestoreFromFile(file) },
                            onDeleteClick = { fileToDelete = file.name }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupStatusRow(status: BackupStatus) {
    val (dotColor, text) = when (status) {
        is BackupStatus.Running ->
            MaterialTheme.colorScheme.primary to "Backup running…"
        is BackupStatus.LastRun ->
            if (status.success)
                MaterialTheme.colorScheme.tertiary to "Last backup: ${formatBackupDate(status.timestamp)}"
            else
                MaterialTheme.colorScheme.error to "Last backup failed"
        is BackupStatus.Never ->
            MaterialTheme.colorScheme.onSurfaceVariant to "No backups yet"
        is BackupStatus.Idle ->
            MaterialTheme.colorScheme.onSurfaceVariant to "No backups yet"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (status is BackupStatus.Running) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = dotColor
            )
        } else {
            Surface(
                modifier = Modifier.size(10.dp),
                shape = CircleShape,
                color = dotColor
            ) {}
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = dotColor)
    }
}

@Composable
private fun RestoreStatusRow(status: RestoreStatus) {
    when (status) {
        is RestoreStatus.None -> return
        is RestoreStatus.Queued -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            Text("Restore queued…", style = MaterialTheme.typography.bodySmall)
        }
        is RestoreStatus.Running -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = if (status.total > 0)
                "${status.phase} — ${"%,d".format(status.done)} / ${"%,d".format(status.total)}"
            else status.phase
            Text(label, style = MaterialTheme.typography.bodySmall)
            if (status.total > 0) {
                LinearProgressIndicator(
                    progress = { status.done.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is RestoreStatus.Finished -> Text(
            if (status.success) "Last restore: ${status.message}"
            else "Restore failed: ${status.message}",
            style = MaterialTheme.typography.bodySmall,
            color = if (status.success) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun BackupFileRow(
    file: BackupFileInfo,
    onRestoreClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodySmall)
            Text(
                "${file.sizeKb} KB · ${formatBackupDate(file.modifiedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRestoreClick) {
            Icon(Icons.Default.Restore, contentDescription = "Restore ${file.name}")
        }
        IconButton(onClick = onDeleteClick) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${file.name}")
        }
    }
}
