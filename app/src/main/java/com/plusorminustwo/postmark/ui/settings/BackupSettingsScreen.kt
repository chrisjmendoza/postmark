package com.plusorminustwo.postmark.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.service.backup.BackupFrequency
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val lastBackupTimestamp = prefs.getLong("last_backup_timestamp", 0L)
    val lastBackupStatus = prefs.getString("last_backup_status", null)
    val lastBackupText = if (lastBackupTimestamp == 0L) "Never" else
        SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(lastBackupTimestamp))

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

            // Status
            Text("Last backup: $lastBackupText", style = MaterialTheme.typography.bodySmall,
                color = if (lastBackupStatus == "FAILED")
                    MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

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
        }
    }
}
