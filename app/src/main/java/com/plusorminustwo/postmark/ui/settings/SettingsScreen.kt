package com.plusorminustwo.postmark.ui.settings

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Star
import com.plusorminustwo.postmark.BuildConfig
import com.plusorminustwo.postmark.util.isDefaultSmsApp
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import com.plusorminustwo.postmark.ui.theme.TimestampPreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onAppearanceClick: () -> Unit,
    onBackupSettingsClick: () -> Unit,
    onDevOptionsClick: () -> Unit,
    onStarredImagesClick: () -> Unit = {},
    onBlockedNumbersClick: () -> Unit = {},
    onSpamClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val themePreference by viewModel.themePreference.collectAsState()
    val timestampPreference by viewModel.timestampPreference.collectAsState()

    val context = LocalContext.current

    // ── Default-SMS state — re-checked on every resume so it reflects changes
    // made in system settings without needing a restart.
    var isDefaultSmsApp by rememberSaveable { mutableStateOf(context.isDefaultSmsApp()) }
    LifecycleResumeEffect(Unit) {
        isDefaultSmsApp = context.isDefaultSmsApp()
        onPauseOrDispose {}
    }

    // Launcher for the system role-request dialog (must use startActivityForResult).
    val roleRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Re-check after the user returns from the system dialog.
        isDefaultSmsApp = context.isDefaultSmsApp()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
            Text(
                text = "SMS/MMS only — RCS chats fall back to standard texting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
            )
            HorizontalDivider()

            SettingsRow(
                icon = { Icon(Icons.Default.Backup, null) },
                title = "Backup",
                subtitle = "Schedule automatic backups",
                onClick = onBackupSettingsClick
            )
            HorizontalDivider()

            SettingsRow(
                icon = { Icon(Icons.Default.Star, null) },
                title = "Starred images",
                subtitle = "View all starred photos",
                onClick = onStarredImagesClick
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
            SettingsRow(
                icon = { Icon(Icons.Default.Palette, null) },
                title = "Appearance",
                subtitle = themePreferenceLabel(themePreference),
                onClick = onAppearanceClick
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

            SettingsSectionHeader(title = "Notifications")
            SettingsRow(
                icon = { Icon(Icons.Default.Notifications, null) },
                title = "Notifications",
                subtitle = "Privacy mode, sound & vibration",
                onClick = onNotificationsClick
            )
            HorizontalDivider()

            SettingsSectionHeader(title = "Privacy")
            SettingsRow(
                icon = { Icon(Icons.Default.Block, null) },
                title = "Blocked numbers",
                subtitle = "View and unblock numbers you've blocked",
                onClick = onBlockedNumbersClick
            )
            HorizontalDivider()

            SettingsRow(
                icon = { Icon(Icons.Default.Report, null) },
                title = "Spam",
                subtitle = "View and restore conversations you've reported",
                onClick = onSpamClick
            )
            HorizontalDivider()

            SettingsSectionHeader(title = "About")
            AboutRow(
                context = context,
                onCopied = {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Build info copied")
                    }
                }
            )
        }
    }
}

// ── AboutRow ───────────────────────────────────────────────────────────────────
// Shows the running build's version + git commit so it's possible to confirm,
// after a Firebase App Distribution push, that the phone actually picked up the
// new build rather than silently staying on a stale one. Tap copies the full
// string to the clipboard for pasting into a bug report.
@Composable
private fun AboutRow(context: android.content.Context, onCopied: () -> Unit) {
    val buildInfo = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA})"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Postmark build", buildInfo))
                onCopied()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Info, contentDescription = null)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Version", style = MaterialTheme.typography.bodyLarge)
            Text(
                buildInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

/** Summary subtitle for the Appearance row — mirrors the labels used in
 *  [AppearanceScreen]'s theme selector. */
private fun themePreferenceLabel(pref: ThemePreference): String = when (pref) {
    ThemePreference.SYSTEM       -> "Follow system"
    ThemePreference.ALWAYS_DARK  -> "Always dark"
    ThemePreference.ALWAYS_LIGHT -> "Always light"
}

/** Standard Material "disabled content" alpha, applied to a whole [SettingsRow] when
 *  [SettingsRow.enabled] is false. */
private const val DISABLED_ROW_ALPHA = 0.38f

/**
 * @param enabled When false, the row is drawn at reduced (Material disabled-content)
 *  alpha and [onClick] is never wired up — used by the Appearance screen's app accent
 *  row while Material You is on (that toggle overrides any accent choice).
 */
@Composable
fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else DISABLED_ROW_ALPHA)
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

/** Shared by [AppearanceScreen] (font family) and this screen (message timestamps). */
@Composable
fun <T> RadioSettingRow(
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
                    if (subtitle.isNotBlank()) {
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
}

/** Shared by [AppearanceScreen] (Material You) and this screen (privacy mode). */
@Composable
fun ToggleSettingRow(
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

