package com.plusorminustwo.postmark.ui.contact

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.formatter.friendlyTimestamp
import com.plusorminustwo.postmark.domain.links.LinkEntry
import com.plusorminustwo.postmark.domain.model.GalleryAttachment
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.ui.components.AccentColorDialog
import com.plusorminustwo.postmark.ui.components.BackgroundPlacementEditor
import com.plusorminustwo.postmark.ui.components.ChatBackgroundDialog
import com.plusorminustwo.postmark.ui.components.ChatBackgroundPreview
import com.plusorminustwo.postmark.ui.components.ChatBackgroundThumbnail
import com.plusorminustwo.postmark.ui.components.ContactAvatar
import com.plusorminustwo.postmark.ui.components.ThemePresetDialog
import com.plusorminustwo.postmark.ui.components.accentSubtitle
import com.plusorminustwo.postmark.ui.components.avatarColor
import com.plusorminustwo.postmark.ui.settings.SettingsRow
import com.plusorminustwo.postmark.ui.theme.isAppInDarkTheme
import com.plusorminustwo.postmark.ui.thread.VideoThumbnailTile
import com.plusorminustwo.postmark.util.openUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── ContactDetailScreen ───────────────────────────────────────────────────────

/**
 * Full-screen contact detail page, opened by tapping the name/avatar in the
 * thread TopAppBar.
 *
 * Shows:
 *  - Large avatar + resolved display name + inline nickname editor
 *  - "Open in Contacts" button (opens system Contacts app; creates if new)
 *  - Mute / Pin / Notifications toggles (mirrors the ⋮ menu)
 *  - Photos / Videos / Links sections (Google Messages-style): each media section shows
 *    a capped 6-item preview grid that opens a full gallery route ([onOpenPhotos] /
 *    [onOpenVideos]) when tapped; Links renders inline (no third route — see
 *    [LinkRow]/docs/TODO.md for why). Empty sections are hidden entirely.
 *
 * @param threadId     Room thread ID passed via the nav graph.
 * @param onBack       Called when the user presses the back arrow.
 * @param onOpenPhotos Called to open the full Photos gallery route for this thread.
 * @param onOpenVideos Called to open the full Videos gallery route for this thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    threadId: Long,
    onBack: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenVideos: () -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel()
) {
    val thread       by viewModel.thread.collectAsState()
    val photos       by viewModel.photos.collectAsState()
    val videos       by viewModel.videos.collectAsState()
    val links        by viewModel.links.collectAsState()
    val context      = LocalContext.current
    val scope        = rememberCoroutineScope()

    // Resolved name for the received side of a Links row: nickname overrides displayName,
    // same precedence as the avatar/name header below.
    val contactLabel = thread?.let { it.nickname ?: formatPhoneNumber(it.displayName) } ?: "Contact"

    // ── Nickname dialog state ──────────────────────────────────────────────────
    var showNicknameDialog by remember { mutableStateOf(false) }
    var nicknameInput      by remember { mutableStateOf("") }

    // ── Accent color dialog state (Their color / Your bubble color) ────────────
    var showAccentColorDialog by remember { mutableStateOf(false) }
    var showSentColorDialog by remember { mutableStateOf(false) }

    // ── Chat background dialog state ──────────────────────────────────────────
    var showChatBackgroundDialog by remember { mutableStateOf(false) }
    var showImageOptions by remember { mutableStateOf(false) }
    val placementRequest by viewModel.placementRequest.collectAsState()
    val isDarkTheme = isAppInDarkTheme()

    // ── Theme preset dialog state ─────────────────────────────────────────────
    var showThemePresetDialog by remember { mutableStateOf(false) }

    // Android Photo Picker for a custom chat-background image — mirrors the ReplyBar
    // attachment launcher (Jetpack-backed, so it works down to minSdk 26). On a result the
    // placement editor opens before anything is saved (see beginPlacementForPick).
    val backgroundImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.beginPlacementForPick(uri)
    }

    // ── Nickname dialog ────────────────────────────────────────────────────────
    if (showNicknameDialog) {
        NicknameDialog(
            initialValue = nicknameInput,
            onConfirm = { value ->
                viewModel.setNickname(value)
                showNicknameDialog = false
            },
            onDismiss = { showNicknameDialog = false }
        )
    }

    // ── Accent color dialog (their color: avatar + received bubbles) ───────────
    if (showAccentColorDialog) {
        thread?.let { t ->
            AccentColorDialog(
                title = "Their color",
                defaultHint = "Default matches their avatar's usual color.",
                currentArgb = t.accentColorArgb,
                onSelect = { argb ->
                    viewModel.setAccentColor(argb)
                    showAccentColorDialog = false
                },
                onDismiss = { showAccentColorDialog = false }
            )
        }
    }

    // ── Sent color dialog (your bubble color) ───────────────────────────────────
    if (showSentColorDialog) {
        thread?.let { t ->
            AccentColorDialog(
                title = "Your bubble color",
                defaultHint = "Default uses the app's sent-bubble color.",
                currentArgb = t.sentColorArgb,
                onSelect = { argb ->
                    viewModel.setSentColor(argb)
                    showSentColorDialog = false
                },
                onDismiss = { showSentColorDialog = false }
            )
        }
    }

    // ── Chat background dialog ────────────────────────────────────────────────
    if (showChatBackgroundDialog) {
        thread?.let { t ->
            val currentImageFile = remember(t.chatBackgroundId) {
                ChatBackgrounds.resolveImageFile(t.chatBackgroundId, viewModel::chatBackgroundImageFile)
            }
            ChatBackgroundDialog(
                currentId = t.chatBackgroundId,
                showFollowGlobal = true,
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
                onCurrentImageOptions = {
                    showChatBackgroundDialog = false
                    showImageOptions = true
                },
                onDismiss = { showChatBackgroundDialog = false }
            )
        }
    }

    // ── Background photo options (adjust placement / choose a different photo) ──
    if (showImageOptions) {
        AlertDialog(
            onDismissRequest = { showImageOptions = false },
            title = { Text("Background photo") },
            text = {
                Column {
                    TextButton(onClick = {
                        showImageOptions = false
                        viewModel.beginPlacementForAdjust()
                    }) { Text("Adjust placement") }
                    TextButton(onClick = {
                        showImageOptions = false
                        backgroundImagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) { Text("Choose a different photo") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImageOptions = false }) { Text("Cancel") }
            }
        )
    }

    // ── Placement editor ──────────────────────────────────────────────────────
    placementRequest?.let { req ->
        BackgroundPlacementEditor(
            model = req.model,
            imageWidth = req.imageWidth,
            imageHeight = req.imageHeight,
            initial = req.initial,
            onAccept = { p, vw, vh -> viewModel.confirmPlacement(p, vw, vh) },
            onCancel = { viewModel.cancelPlacement() }
        )
    }

    // ── Theme preset dialog ───────────────────────────────────────────────────
    // Applies a ready-made combo by COPYING all three look fields onto this thread via
    // the same per-thread setters the individual rows use. A null preset background maps
    // to ChatBackgrounds.None.id — the exact path the background dialog's "None" tile uses.
    if (showThemePresetDialog) {
        ThemePresetDialog(
            isDarkTheme = isDarkTheme,
            onPresetSelected = { preset ->
                viewModel.setAccentColor(preset.contactArgb)
                viewModel.setSentColor(preset.sentArgb)
                viewModel.setChatBackground(preset.backgroundId ?: ChatBackgrounds.None.id)
                showThemePresetDialog = false
            },
            onDismiss = { showThemePresetDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Avatar + name ──────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(12.dp))
                thread?.let { t ->
                    // Resolved name: nickname overrides displayName everywhere.
                    val resolvedName = t.nickname ?: formatPhoneNumber(t.displayName)

                    ContactAvatar(
                        address   = t.address,
                        name      = resolvedName,
                        size      = 80.dp,
                        overrideColor = t.accentColorArgb?.let { Color(it) }
                    )

                    Spacer(Modifier.height(12.dp))

                    // Name row with inline "edit nickname" pencil button.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(
                            text  = resolvedName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign  = TextAlign.Center
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                nicknameInput = t.nickname ?: ""
                                showNicknameDialog = true
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit nickname",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Raw phone number (shown when a nickname or contact name differs from address).
                    if (t.address != resolvedName) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text  = formatPhoneNumber(t.address),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Label clarifying that the nickname is Postmark-only.
                    if (t.nickname != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text  = "Nickname · Postmark only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Open in Contacts ───────────────────────────────────────────────
            item {
                OutlinedButton(
                    onClick = {
                        val address = thread?.address ?: return@OutlinedButton
                        openInContacts(context, address, scope)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Open in Contacts")
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── Actions ────────────────────────────────────────────────────────
            item {
                HorizontalDivider()
                thread?.let { t ->
                    val backgroundImageFile = remember(t.chatBackgroundId) {
                        ChatBackgrounds.resolveImageFile(t.chatBackgroundId, viewModel::chatBackgroundImageFile)
                    }
                    ContactActionsSection(
                        thread              = t,
                        isDarkTheme         = isDarkTheme,
                        backgroundImageFile = backgroundImageFile,
                        onToggleMute        = viewModel::toggleMute,
                        onTogglePin         = viewModel::togglePin,
                        onToggleNotifications = viewModel::toggleNotifications,
                        onOpenThemePresetPicker = { showThemePresetDialog = true },
                        onOpenColorPicker   = { showAccentColorDialog = true },
                        onOpenSentColorPicker = { showSentColorDialog = true },
                        onOpenChatBackgroundPicker = { showChatBackgroundDialog = true }
                    )
                }
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
            }

            // ── Photos section (Google Messages-style) ─────────────────────────
            // Preview capped at 6, newest first; tapping anywhere in the section opens
            // the full Photos gallery route ([onOpenPhotos]) rather than the viewer
            // directly — matching Google Messages, where the profile preview is a
            // doorway into the full grid, not the viewer itself. Hidden entirely when empty.
            if (photos.isNotEmpty()) {
                item {
                    MediaSectionPreview(
                        title = "Photos",
                        count = photos.size,
                        items = photos.take(6),
                        onOpenGallery = onOpenPhotos
                    ) { attachment -> PhotoPreviewTile(attachment) }
                }
            }

            // ── Videos section ──────────────────────────────────────────────────
            // Same preview(6) → full-gallery pattern as Photos, but never mixed with
            // it — a thread with both gets two separate sections.
            if (videos.isNotEmpty()) {
                item {
                    MediaSectionPreview(
                        title = "Videos",
                        count = videos.size,
                        items = videos.take(6),
                        onOpenGallery = onOpenVideos
                    ) { attachment -> VideoPreviewTile(attachment) }
                }
            }

            // ── Links section ────────────────────────────────────────────────────
            // Renders every link inline rather than capping + a third gallery route:
            // full grid parity was only asked for Photos/Videos, and a link list has no
            // grid layout to make a dedicated route worth its own screen/ViewModel/nav
            // entry for. See docs/TODO.md for the full writeup of this decision.
            if (links.isNotEmpty()) {
                item {
                    Text(
                        text = "Links · ${links.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                itemsIndexed(
                    items = links,
                    // messageId+url isn't unique alone if the same link appears twice in
                    // one body; the index breaks that tie.
                    key = { index, link -> "${link.messageId}_${link.url}_$index" }
                ) { _, link ->
                    LinkRow(
                        link = link,
                        contactName = contactLabel,
                        onClick = { context.openUrl(link.url) }
                    )
                }
            }

            // Bottom breathing room, but only when at least one section rendered above.
            if (photos.isNotEmpty() || videos.isNotEmpty() || links.isNotEmpty()) {
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// ── NicknameDialog ────────────────────────────────────────────────────────────

/**
 * AlertDialog with a single text field for entering or clearing a nickname.
 *
 * Submitting an empty string clears the existing nickname (falls back to displayName).
 *
 * @param initialValue  Pre-filled text — the current nickname, or "" if none set.
 * @param onConfirm     Called with the entered value (may be blank = clear).
 * @param onDismiss     Called when the dialog is dismissed without saving.
 */
@Composable
private fun NicknameDialog(
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit nickname") },
        text = {
            Column {
                Text(
                    text  = "Visible only inside Postmark. Leave blank to clear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value         = text,
                    onValueChange = { text = it },
                    label         = { Text("Nickname") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── ContactActionsSection ─────────────────────────────────────────────────────

/**
 * Three toggle rows that mirror the ⋮ menu actions from [ThreadScreen]:
 * Mute, Pin, and Notifications — plus the "Their color" / "Your bubble color" /
 * "Chat background" picker rows.
 *
 * Each toggle row uses a leading icon, a label, and a trailing Switch for clarity.
 */
@Composable
private fun ContactActionsSection(
    thread: Thread,
    isDarkTheme: Boolean,
    backgroundImageFile: File?,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleNotifications: () -> Unit,
    onOpenThemePresetPicker: () -> Unit,
    onOpenColorPicker: () -> Unit,
    onOpenSentColorPicker: () -> Unit,
    onOpenChatBackgroundPicker: () -> Unit
) {
    // Mute toggle row.
    ContactActionRow(
        icon    = Icons.Default.NotificationsOff,
        label   = "Mute conversation",
        checked = thread.isMuted,
        onToggle = onToggleMute
    )

    // Pin toggle row.
    ContactActionRow(
        icon    = Icons.Default.PushPin,
        label   = "Pin to top",
        checked = thread.isPinned,
        onToggle = onTogglePin
    )

    // Notifications enabled/disabled toggle row.
    ContactActionRow(
        icon    = Icons.Default.Notifications,
        label   = "Notifications",
        checked = thread.notificationsEnabled,
        onToggle = onToggleNotifications
    )

    // Theme preset row — applies a ready-made sent/contact/background combo in one tap,
    // copying the values onto this thread. The individual override rows below fine-tune it.
    SettingsRow(
        icon = {
            Icon(
                imageVector = Icons.Default.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        },
        title = "Theme preset",
        subtitle = "Ready-made color combos",
        onClick = onOpenThemePresetPicker
    )

    // Their color row — opens the swatch-grid picker. Effective color is the custom
    // accent if set, else the default hash-derived avatarColor. Applies to the
    // contact's avatar (everywhere) and their received bubbles in this thread.
    val accentArgb = thread.accentColorArgb
    SettingsRow(
        icon = {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(accentArgb?.let { Color(it) } ?: avatarColor(thread.address), CircleShape)
            )
        },
        title = "Their color",
        subtitle = accentSubtitle(accentArgb, "Applies to their avatar and message bubbles"),
        onClick = onOpenColorPicker
    )

    // Your bubble color row — opens the same swatch-grid picker for the independent
    // sent-bubble override. Effective color is the custom sentColorArgb if set, else
    // the default primaryContainer sent-bubble fill.
    val sentArgb = thread.sentColorArgb
    SettingsRow(
        icon = {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(sentArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primaryContainer, CircleShape)
            )
        },
        title = "Your bubble color",
        subtitle = accentSubtitle(sentArgb, "Applies to your sent messages"),
        onClick = onOpenSentColorPicker
    )

    // Chat background row — opens the preview-tile picker. thread.chatBackgroundId null
    // means "no override" (follow global); an "image:" id is a custom photo; else a
    // built-in catalog entry. The subtitle names whichever applies.
    val backgroundId = thread.chatBackgroundId
    val isImageBackground = ChatBackgrounds.isImageId(backgroundId)
    val resolvedBackground = ChatBackgrounds.resolve(backgroundId)
    SettingsRow(
        icon = {
            if (isImageBackground) {
                ChatBackgroundThumbnail(
                    file = backgroundImageFile,
                    modifier = Modifier.size(width = 28.dp, height = 22.dp)
                )
            } else {
                ChatBackgroundPreview(
                    background = resolvedBackground,
                    isDarkTheme = isDarkTheme,
                    modifier = Modifier.size(width = 28.dp, height = 22.dp)
                )
            }
        },
        title = "Chat background",
        subtitle = when {
            backgroundId == null -> "Default"
            isImageBackground    -> ChatBackgrounds.CUSTOM_IMAGE_LABEL
            else                 -> resolvedBackground.displayName
        },
        onClick = onOpenChatBackgroundPicker
    )
}

/**
 * A single labelled toggle row used inside [ContactActionsSection].
 *
 * @param icon     Leading icon for the action.
 * @param label    Human-readable action name.
 * @param checked  Current toggle state.
 * @param onToggle Called when the user taps anywhere on the row.
 */
@Composable
private fun ContactActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier           = Modifier.size(22.dp)
            )
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Switch(
            checked         = checked,
            onCheckedChange = { onToggle() }
        )
    }
}

// ── MediaSectionPreview ───────────────────────────────────────────────────────

/**
 * A Photos/Videos section: a header ("Photos · 34") plus a 6-item preview grid (3
 * columns, chunked into up to 2 rows — matches the thread's own bubble-grid technique
 * of plain `Row`s rather than a nested `LazyVerticalGrid`, avoiding nested-scroll
 * conflicts inside the outer `LazyColumn`).
 *
 * The ENTIRE section — header and grid alike — is one tap target: tapping anywhere
 * calls [onOpenGallery] to open the full gallery route, rather than opening a viewer
 * directly from a preview tile (Google Messages treats the profile preview as a doorway
 * into the full grid, not the viewer itself). A trailing chevron on the header makes
 * that tappability visible rather than relying on an implicit "text looks pressable" cue.
 *
 * @param items Already capped to the preview size (6) by the caller.
 * @param tile  Renders one preview cell for a [T] — [PhotoPreviewTile] or [VideoPreviewTile].
 */
@Composable
private fun <T> MediaSectionPreview(
    title: String,
    count: Int,
    items: List<T>,
    onOpenGallery: () -> Unit,
    tile: @Composable (T) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenGallery)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text  = "$title · $count",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open all $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        items.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f)) { tile(item) }
                }
                // Fill empty slots in the last row so items stay left-aligned.
                repeat(3 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** One square Photos-preview cell — plain Coil thumbnail; the whole section (not this
 *  tile) is the tap target, see [MediaSectionPreview]. */
@Composable
private fun PhotoPreviewTile(attachment: GalleryAttachment) {
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(Uri.parse(attachment.uri))
            .size(280, 280)
            .crossfade(true)
            .build(),
        contentDescription = "Photo",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/** One square Videos-preview cell — the shared first-frame-still + play-badge tile
 *  (see [VideoThumbnailTile]); the whole section (not this tile) is the tap target. */
@Composable
private fun VideoPreviewTile(attachment: GalleryAttachment) {
    VideoThumbnailTile(
        uri = attachment.uri,
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

// ── LinkRow ────────────────────────────────────────────────────────────────────

/**
 * One row in the Links section: the URL (single line, ellipsized), then a sender label
 * + friendly date underneath. Tapping opens the URL via [openUrl][com.plusorminustwo.postmark.util.openUrl].
 *
 * @param contactName Resolved display name for received links; sent links show "You".
 */
@Composable
private fun LinkRow(
    link: LinkEntry,
    contactName: String,
    onClick: () -> Unit
) {
    val friendly = remember(link.timestamp) {
        friendlyTimestamp(
            timestampMs = link.timestamp,
            nowMs = System.currentTimeMillis()
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = link.url,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${if (link.isSent) "You" else contactName} · $friendly",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── ContactFullScreenViewer ───────────────────────────────────────────────────

/**
 * Full-screen overlay that displays an MMS image with pinch-to-zoom and pan support.
 *
 * A black scrim fills the screen. The user can:
 *  - Pinch to zoom (1× – 5×)
 *  - Pan while zoomed in
 *  - Tap the scrim or the close button to dismiss
 *
 * @param uri       content:// URI for the media to display.
 * @param onDismiss Called when the user closes the viewer.
 */
@Composable
internal fun ContactFullScreenViewer(uri: String, onDismiss: () -> Unit) {
    var scale   by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        // Without this, the dialog window is constrained to the platform's default
        // (non-fullscreen) size — see the identical fix in ThreadScreen's viewers.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // See FullScreenImageViewer in ThreadScreen — the dialog's own Window must stop
        // fitting system windows for it to actually draw edge-to-edge behind the bars.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        }
        val context = LocalContext.current
        // Remembered so pinch/pan recompositions don't rebuild the request per frame.
        val imageRequest = remember(uri) {
            ImageRequest.Builder(context)
                .data(Uri.parse(uri))
                .crossfade(true)
                .build()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = "Full-screen media",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxSize()
                    // Absorb taps on the image so they don't fall through to the scrim dismiss.
                    .clickable { /* absorb */ }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    // Lambda overload defers the state reads to the draw phase — the
                    // parameter overload recomposed this node on every gesture frame.
                    .graphicsLayer {
                        scaleX       = scale
                        scaleY       = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            )
            // Close button in top-right corner as a secondary affordance.
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.Close,
                    contentDescription = "Close",
                    tint               = Color.White
                )
            }
        }
    }
}

// ── openInContacts ────────────────────────────────────────────────────────────

/**
 * Opens the system Contacts app for [address].
 *
 * If the number already exists as a contact, opens their contact card (ACTION_VIEW).
 * If unknown, opens the "Create contact" screen pre-filled with the number
 * (ACTION_INSERT_OR_EDIT) so the user can add it without manual typing.
 *
 * The PhoneLookup query is run on [Dispatchers.IO] to avoid blocking the main thread.
 * The Intent is fired back on the main thread via [withContext].
 *
 * Note: we never write to the system Contacts database ourselves — the user's changes
 * in the Contacts app are their own. Our [Thread.nickname] stays Postmark-only.
 */
private fun openInContacts(context: Context, address: String, scope: CoroutineScope) {
    scope.launch(Dispatchers.IO) {
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )

        // Query system Contacts for an existing entry matching this number.
        val contactUri = runCatching {
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.CONTACT_ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.CONTACT_ID)
                    )
                    ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
                } else null
            }
        }.getOrNull()

        // Launch the appropriate Intent on the main thread.
        withContext(Dispatchers.Main) {
            val intent = if (contactUri != null) {
                // Known contact → open their full card.
                Intent(Intent.ACTION_VIEW, contactUri)
            } else {
                // Unknown number → pre-fill the "Create contact" screen.
                addContactIntent(address)
            }
            context.startActivity(intent)
        }
    }
}

/**
 * Builds the "Create contact" Intent (ACTION_INSERT_OR_EDIT) pre-filled with [address], so the
 * user can add it without manual typing. Shared by [openInContacts] above (unknown-number branch)
 * and the thread "Save number?" banner (ThreadScreen) — both cases only ever reach this when the
 * number is already known to have no matching contact, so there is no ACTION_VIEW branch here.
 */
internal fun addContactIntent(address: String): Intent =
    Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
        type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
        putExtra(ContactsContract.Intents.Insert.PHONE, address)
    }

