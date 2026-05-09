package com.plusorminustwo.postmark.ui.settings

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.data.preferences.BubbleFontScaleRepository
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import com.plusorminustwo.postmark.ui.theme.TimestampPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackupSettingsClick: () -> Unit,
    onDevOptionsClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val themePreference by viewModel.themePreference.collectAsState()
    val timestampPreference by viewModel.timestampPreference.collectAsState()
    val privacyModeEnabled by viewModel.privacyModeEnabled.collectAsState()
    val bubbleFontScale by viewModel.bubbleFontScale.collectAsState()

    val context = LocalContext.current

    // ── Default-SMS state — re-checked on every resume so it reflects changes
    // made in system settings without needing a restart.
    fun checkIsDefault() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_SMS) == true
    } else {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    var isDefaultSmsApp by rememberSaveable { mutableStateOf(checkIsDefault()) }
    LifecycleResumeEffect(Unit) {
        isDefaultSmsApp = checkIsDefault()
        onPauseOrDispose {}
    }

    // Launcher for the system role-request dialog (must use startActivityForResult).
    val roleRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Re-check after the user returns from the system dialog.
        isDefaultSmsApp = checkIsDefault()
    }

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
        Column(modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)) {

            // ── General ───────────────────────────────────────────────────────
            SettingsSectionHeader(title = "General")
            if (isDefaultSmsApp) {
                // Already the default — show a non-tappable confirmation row.
                DefaultSmsStatusRow(isDefault = true, onClick = {})
            } else {
                // Not the default — tapping launches the system role-request dialog.
                DefaultSmsStatusRow(
                    isDefault = false,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            context.getSystemService(RoleManager::class.java)
                                ?.createRequestRoleIntent(RoleManager.ROLE_SMS)
                                ?.let { roleRequestLauncher.launch(it) }
                        } else {
                            context.startActivity(
                                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                                    .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
                            )
                        }
                    }
                )
            }
            HorizontalDivider()

            SettingsRow(
                icon = { Icon(Icons.Default.Backup, null) },
                title = "Backup",
                subtitle = "Schedule automatic backups",
                onClick = onBackupSettingsClick
            )
            HorizontalDivider()

            SettingsSectionHeader(title = "Developer")
            SettingsRow(
                icon = { Icon(Icons.Default.Code, null) },
                title = "Developer options",
                subtitle = "Sample data, sync controls",
                onClick = onDevOptionsClick
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

            FontScaleSettingRow(
                scale = bubbleFontScale,
                onScaleChange = viewModel::setBubbleFontScale,
                onReset = viewModel::resetBubbleFontScale
            )
            HorizontalDivider()

            SettingsSectionHeader(title = "Notifications")
            ToggleSettingRow(
                icon = { Icon(Icons.Default.Lock, null) },
                title = "Privacy mode",
                subtitle = "Show \"New message\" without sender or preview",
                checked = privacyModeEnabled,
                onCheckedChange = viewModel::setPrivacyMode
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

@Composable
private fun ToggleSettingRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── DefaultSmsStatusRow ───────────────────────────────────────────────────────
// Shows whether Postmark is the default SMS app. When isDefault=false the row
// is tappable and launches the system role-request dialog (onClick handles it).
@Composable
private fun DefaultSmsStatusRow(isDefault: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!isDefault) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Green check when default, plain message icon when not.
        if (isDefault) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(Icons.Default.Message, contentDescription = null)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Default SMS app", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (isDefault) "Postmark is your default SMS app"
                       else "Tap to set Postmark as your default SMS app",
                style = MaterialTheme.typography.bodySmall,
                color = if (isDefault) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!isDefault) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── FontScaleSettingRow ───────────────────────────────────────────────────────

/**
 * Settings row for adjusting bubble text size.
 *
 * Shows:
 *  - A header row with a format-size icon, title, and "Reset" button
 *  - A [Slider] spanning MIN_SCALE (0.8) to MAX_SCALE (1.6)
 *  - A small preview bubble with sample text at the current scale so the
 *    user sees the effect before leaving the screen
 *
 * @param scale        Current scale value (0.8–1.6).
 * @param onScaleChange Called on every slider position change (live update).
 * @param onReset      Called when the user taps "Reset" — restores scale to 1.0.
 */
@Composable
private fun FontScaleSettingRow(
    scale: Float,
    onScaleChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Header: icon + title + Reset button ──────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.FormatSize, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Text size", style = MaterialTheme.typography.bodyLarge)
                Text(
                    // Show percentage so users have a concrete reference (100% = default).
                    "%.0f%%".format(scale * 100f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Reset button — only enabled when scale differs from default.
            TextButton(
                onClick = onReset,
                enabled = scale != BubbleFontScaleRepository.DEFAULT_SCALE
            ) {
                Text("Reset")
            }
        }

        // ── Slider ────────────────────────────────────────────────────────────
        // Steps = 8 gives quarter-turn detents at 0.80, 0.90, 1.00, 1.10, 1.20, 1.30, 1.40, 1.50, 1.60
        Slider(
            value = scale,
            onValueChange = onScaleChange,
            valueRange = BubbleFontScaleRepository.MIN_SCALE..BubbleFontScaleRepository.MAX_SCALE,
            steps = 7,   // 9 positions → 7 internal steps
            modifier = Modifier.fillMaxWidth()
        )

        // ── Preview bubble ────────────────────────────────────────────────────
        // Mimics a received message bubble so the user sees exactly how their
        // messages will look at the chosen scale.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.wrapContentWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "Hey, are you free this weekend?",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize * scale
                    )
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Preview",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize * scale
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
