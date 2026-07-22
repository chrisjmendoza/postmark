package com.plusorminustwo.postmark.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.PostmarkApplication

/**
 * Consolidated notification settings, reached from Settings › Notifications.
 *
 * Postmark's own preferences (privacy mode) live here alongside deep links into
 * the system per-app / per-channel notification screens, because on Android 8+
 * sound and vibration are properties of a notification CHANNEL, not app-level
 * toggles — so there is nothing for the app to own there; it can only hand the
 * user off to the system UI that does. Per-conversation sound/vibration would
 * require per-thread channels (see the deferral note in the codebase TODO).
 *
 * Privacy-mode state is shared with the main Settings screen via [SettingsViewModel];
 * this screen relocates the toggle rather than duplicating the preference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val privacyModeEnabled by viewModel.privacyModeEnabled.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        // Scaffold padding carries the status-bar (top, below the app bar) and
        // nav-bar (bottom) insets to the scrolling content on all four edges.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
        ) {
            ToggleSettingRow(
                icon = { Icon(Icons.Default.Lock, null) },
                title = "Privacy mode",
                subtitle = "Show \"New message\" without sender or preview",
                checked = privacyModeEnabled,
                onCheckedChange = viewModel::setPrivacyMode
            )
            HorizontalDivider()

            // Opens the system per-app notification screen, where the user controls
            // sound, vibration, importance and per-channel toggles. EXTRA_APP_PACKAGE
            // is required by ACTION_APP_NOTIFICATION_SETTINGS.
            SettingsRow(
                icon = { Icon(Icons.Default.Notifications, null) },
                title = "Manage notification channels",
                subtitle = "Sound, vibration and importance in system settings",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            )
            HorizontalDivider()

            // Deep link straight to the "Incoming messages" channel so the sound &
            // vibration controls for new texts are one tap away.
            SettingsRow(
                icon = { Icon(Icons.Default.NotificationsActive, null) },
                title = "Incoming message sound & vibration",
                subtitle = "Open the incoming-messages channel",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(
                                Settings.EXTRA_CHANNEL_ID,
                                PostmarkApplication.CHANNEL_INCOMING_SMS
                            )
                    )
                }
            )
            HorizontalDivider()

            Text(
                text = "Per-conversation notification toggles live in each " +
                    "conversation's ⋮ menu — mute and disable-notifications are " +
                    "per-conversation there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
            )
        }
    }
}
