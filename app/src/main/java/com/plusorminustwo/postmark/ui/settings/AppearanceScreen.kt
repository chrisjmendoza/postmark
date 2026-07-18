package com.plusorminustwo.postmark.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.data.preferences.BubbleFontScaleRepository
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds
import com.plusorminustwo.postmark.ui.components.ChatBackgroundDialog
import com.plusorminustwo.postmark.ui.components.ChatBackgroundPreview
import com.plusorminustwo.postmark.ui.theme.FontFamilyPreference
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import com.plusorminustwo.postmark.ui.theme.isAppInDarkTheme

/**
 * Appearance settings screen: theme, font family, message text size, and the global
 * default chat background. Reached from Settings' single "Appearance" summary row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    viewModel: AppearanceViewModel = hiltViewModel()
) {
    val themePreference by viewModel.themePreference.collectAsState()
    val fontFamilyPreference by viewModel.fontFamilyPreference.collectAsState()
    val bubbleFontScale by viewModel.bubbleFontScale.collectAsState()
    val globalChatBackgroundId by viewModel.globalChatBackgroundId.collectAsState()

    val isDarkTheme = isAppInDarkTheme()
    var showChatBackgroundDialog by remember { mutableStateOf(false) }

    if (showChatBackgroundDialog) {
        ChatBackgroundDialog(
            // No "Default" cell here (showFollowGlobal=false), so normalize the
            // repository's null ("unset") to ChatBackgrounds.None's id so the None
            // tile shows as selected rather than nothing.
            currentId = ChatBackgrounds.fromGlobalPreferenceId(globalChatBackgroundId),
            showFollowGlobal = false,
            isDarkTheme = isDarkTheme,
            onSelect = { id ->
                viewModel.setChatBackground(id)
                showChatBackgroundDialog = false
            },
            onDismiss = { showChatBackgroundDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
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

            RadioSettingRow(
                icon = { Icon(Icons.Default.Palette, null) },
                title = "Theme",
                options = listOf(
                    Triple(ThemePreference.SYSTEM,       "Follow system", ""),
                    Triple(ThemePreference.ALWAYS_DARK,  "Always dark",   ""),
                    Triple(ThemePreference.ALWAYS_LIGHT, "Always light",  "")
                ),
                current = themePreference,
                onSelect = viewModel::setTheme
            )
            HorizontalDivider()

            RadioSettingRow(
                icon = { Icon(Icons.Default.TextFields, null) },
                title = "Font family",
                options = listOf(
                    Triple(FontFamilyPreference.SYSTEM,    "System default", "Uses your device's default font"),
                    Triple(FontFamilyPreference.SERIF,     "Serif",          "A traditional serif typeface"),
                    Triple(FontFamilyPreference.MONOSPACE, "Monospace",      "Fixed-width, code-style font")
                ),
                current = fontFamilyPreference,
                onSelect = viewModel::setFontFamily
            )
            HorizontalDivider()

            FontScaleSettingRow(
                scale = bubbleFontScale,
                onScaleChange = viewModel::setBubbleFontScale,
                onReset = viewModel::resetBubbleFontScale
            )
            HorizontalDivider()

            val globalChatBackground = ChatBackgrounds.resolve(globalChatBackgroundId)
            SettingsRow(
                icon = {
                    ChatBackgroundPreview(
                        background = globalChatBackground,
                        isDarkTheme = isDarkTheme,
                        modifier = Modifier.size(width = 28.dp, height = 22.dp)
                    )
                },
                title = "Chat background",
                subtitle = globalChatBackground.displayName,
                onClick = { showChatBackgroundDialog = true }
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
