package com.plusorminustwo.postmark.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RoundedCorner
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plusorminustwo.postmark.data.preferences.BubbleFontScaleRepository
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds
import com.plusorminustwo.postmark.ui.components.AccentColorDialog
import com.plusorminustwo.postmark.ui.components.ChatBackgroundDialog
import com.plusorminustwo.postmark.ui.components.ChatBackgroundPreview
import com.plusorminustwo.postmark.ui.components.ChatBackgroundThumbnail
import com.plusorminustwo.postmark.ui.components.accentSubtitle
import com.plusorminustwo.postmark.ui.theme.BubbleStylePreference
import com.plusorminustwo.postmark.ui.theme.FontFamilyPreference
import com.plusorminustwo.postmark.ui.theme.ThemePreference
import com.plusorminustwo.postmark.ui.theme.isAppInDarkTheme

/** Postmark's default sent-bubble/primary accent (matches ContactPalette's "Blue"
 *  preset and Theme.kt's brand `AccentBlue`) — shown as the leading swatch for the
 *  "App accent color" row when no override is set. */
private const val DEFAULT_APP_ACCENT_ARGB = 0xFF378ADD.toInt()

/**
 * Appearance settings screen: theme, font family, bubble style, message text size, and
 * the global default chat background. Reached from Settings' single "Appearance" summary row.
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
    val bubbleStyle by viewModel.bubbleStyle.collectAsState()
    val globalChatBackgroundId by viewModel.globalChatBackgroundId.collectAsState()
    val useDynamicColor by viewModel.useDynamicColor.collectAsState()
    val appAccentArgb by viewModel.appAccentArgb.collectAsState()

    val isDarkTheme = isAppInDarkTheme()
    var showChatBackgroundDialog by remember { mutableStateOf(false) }
    var showAppAccentDialog by remember { mutableStateOf(false) }

    // Android Photo Picker for a custom global chat-background image (Jetpack-backed,
    // works down to minSdk 26). A save failure is a silent no-op (see setImageBackground).
    val backgroundImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.setImageBackground(uri)
    }

    if (showChatBackgroundDialog) {
        val currentImageFile = remember(globalChatBackgroundId) {
            ChatBackgrounds.resolveImageFile(globalChatBackgroundId, viewModel::chatBackgroundImageFile)
        }
        ChatBackgroundDialog(
            // No "Default" cell here (showFollowGlobal=false), so normalize the
            // repository's null ("unset") to ChatBackgrounds.None's id so the None
            // tile shows as selected rather than nothing.
            currentId = ChatBackgrounds.fromGlobalPreferenceId(globalChatBackgroundId),
            showFollowGlobal = false,
            isDarkTheme = isDarkTheme,
            currentImageFile = currentImageFile,
            onSelect = { id ->
                viewModel.setChatBackground(id)
                showChatBackgroundDialog = false
            },
            onPickImage = {
                showChatBackgroundDialog = false
                backgroundImagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onDismiss = { showChatBackgroundDialog = false }
        )
    }

    // Only reachable while Material You is off — the row that opens this is disabled
    // (and non-clickable) whenever useDynamicColor is true, so this dialog can't be
    // shown at the same time dynamic color would be overriding the choice anyway.
    if (showAppAccentDialog) {
        AccentColorDialog(
            title = "App accent color",
            defaultHint = "Default uses Postmark's own accent blue.",
            currentArgb = appAccentArgb,
            onSelect = { argb ->
                viewModel.setAppAccent(argb)
                showAppAccentDialog = false
            },
            onDismiss = { showAppAccentDialog = false }
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

            // Material You is only available on API 31+ (Android 12) — the platform
            // APIs `dynamicDarkColorScheme`/`dynamicLightColorScheme` require it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ToggleSettingRow(
                    icon = { Icon(Icons.Default.AutoAwesome, null) },
                    title = "Material You colors",
                    subtitle = "Use system wallpaper colors",
                    checked = useDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor
                )
                HorizontalDivider()
            }

            SettingsRow(
                icon = {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(Color(appAccentArgb ?: DEFAULT_APP_ACCENT_ARGB), CircleShape)
                    )
                },
                title = "App accent color",
                subtitle = if (useDynamicColor) "Controlled by Material You" else accentSubtitle(appAccentArgb, "Default"),
                onClick = { showAppAccentDialog = true },
                enabled = !useDynamicColor
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

            RadioSettingRow(
                icon = { Icon(Icons.Default.RoundedCorner, null) },
                title = "Bubble style",
                options = listOf(
                    Triple(BubbleStylePreference.ROUNDED, "Rounded", "Postmark default"),
                    Triple(BubbleStylePreference.PILL,    "Pill",    "Fully rounded"),
                    Triple(BubbleStylePreference.SQUARE,  "Square",  "Minimal corners")
                ),
                current = bubbleStyle,
                onSelect = viewModel::setBubbleStyle
            )
            HorizontalDivider()

            FontScaleSettingRow(
                scale = bubbleFontScale,
                onScaleChange = viewModel::setBubbleFontScale,
                onReset = viewModel::resetBubbleFontScale
            )
            HorizontalDivider()

            val isImageBackground = ChatBackgrounds.isImageId(globalChatBackgroundId)
            val globalChatBackground = ChatBackgrounds.resolve(globalChatBackgroundId)
            val backgroundImageFile = remember(globalChatBackgroundId) {
                ChatBackgrounds.resolveImageFile(globalChatBackgroundId, viewModel::chatBackgroundImageFile)
            }
            SettingsRow(
                icon = {
                    if (isImageBackground) {
                        ChatBackgroundThumbnail(
                            file = backgroundImageFile,
                            modifier = Modifier.size(width = 28.dp, height = 22.dp)
                        )
                    } else {
                        ChatBackgroundPreview(
                            background = globalChatBackground,
                            isDarkTheme = isDarkTheme,
                            modifier = Modifier.size(width = 28.dp, height = 22.dp)
                        )
                    }
                },
                title = "Chat background",
                subtitle = if (isImageBackground) ChatBackgrounds.CUSTOM_IMAGE_LABEL else globalChatBackground.displayName,
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
