package com.plusorminustwo.postmark.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import com.plusorminustwo.postmark.ui.theme.TimestampPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackupSettingsClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val themePreference by viewModel.themePreference.collectAsState()
    val timestampPreference by viewModel.timestampPreference.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            SettingsRow(
                icon = { Icon(Icons.Default.Backup, null) },
                title = "Backup",
                subtitle = "Schedule automatic backups",
                onClick = onBackupSettingsClick
            )
            HorizontalDivider()

            SettingsSectionHeader(title = "Appearance")
            AppearanceRow(
                icon = { Icon(Icons.Default.Palette, null) },
                title = "Theme",
                current = themePreference,
                onSelect = viewModel::setTheme
            )
            HorizontalDivider()

            SettingsSectionHeader(title = "Conversation")
            RadioSettingRow(
                icon = { Icon(Icons.Default.ChatBubbleOutline, null) },
                title = "Message timestamps",
                options = listOf(
                    Triple(TimestampPreference.ALWAYS,  "Always",       "Time shown under every message"),
                    Triple(TimestampPreference.ON_TAP,  "Tap to reveal","Tap a bubble to show its time"),
                    Triple(TimestampPreference.NEVER,   "Never",        "No timestamps shown")
                ),
                current = timestampPreference,
                onSelect = viewModel::setTimestamp
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun AppearanceRow(
    icon: @Composable () -> Unit,
    title: String,
    current: ThemePreference,
    onSelect: (ThemePreference) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(modifier = Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }

        ThemeOption(
            label = "Follow system",
            selected = current == ThemePreference.SYSTEM,
            onClick = { onSelect(ThemePreference.SYSTEM) }
        )
        ThemeOption(
            label = "Always dark",
            selected = current == ThemePreference.ALWAYS_DARK,
            onClick = { onSelect(ThemePreference.ALWAYS_DARK) }
        )
        ThemeOption(
            label = "Always light",
            selected = current == ThemePreference.ALWAYS_LIGHT,
            onClick = { onSelect(ThemePreference.ALWAYS_LIGHT) }
        )
    }
}

@Composable
private fun ThemeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun <T> RadioSettingRow(
    icon: @Composable () -> Unit,
    title: String,
    options: List<Triple<T, String, String>>,  // value, label, subtitle
    current: T,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(modifier = Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
        options.forEach { (value, label, subtitle) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = current == value, onClick = { onSelect(value) })
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
