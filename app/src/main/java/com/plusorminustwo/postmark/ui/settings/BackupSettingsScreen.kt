package com.plusorminustwo.postmark.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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

    var fileToDelete by remember { mutableStateOf<String?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

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
        }
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
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Charging only", modifier = Modifier.weight(1f))
                    Switch(checked = requireCharging, onCheckedChange = {
                        requireCharging = it
                        prefs.edit().putBoolean("require_charging", it).apply()
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

            // Storage path
            val backupDir = context.getExternalFilesDir("backups")?.absolutePath ?: ""
            Text("Storage: $backupDir",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

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
private fun BackupFileRow(file: BackupFileInfo, onDeleteClick: () -> Unit) {
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
        IconButton(onClick = onDeleteClick) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${file.name}")
        }
    }
}

