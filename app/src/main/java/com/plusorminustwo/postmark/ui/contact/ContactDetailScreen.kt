package com.plusorminustwo.postmark.ui.contact

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.plusorminustwo.postmark.domain.customization.ContactPalette
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.ui.components.ChatBackgroundDialog
import com.plusorminustwo.postmark.ui.components.ChatBackgroundPreview
import com.plusorminustwo.postmark.ui.components.ContactAvatar
import com.plusorminustwo.postmark.ui.components.avatarColor
import com.plusorminustwo.postmark.ui.settings.SettingsRow
import com.plusorminustwo.postmark.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── ContactDetailScreen ───────────────────────────────────────────────────────

/**
 * Full-screen contact detail page, opened by tapping the name/avatar in the
 * thread TopAppBar.
 *
 * Shows:
 *  - Large avatar + resolved display name + inline nickname editor
 *  - "Open in Contacts" button (opens system Contacts app; creates if new)
 *  - Mute / Pin / Notifications toggles (mirrors the ⋮ menu)
 *  - Shared-media grid (images, video, audio) with tap-to-full-screen for images
 *
 * @param threadId  Room thread ID passed via the nav graph.
 * @param onBack    Called when the user presses the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    threadId: Long,
    onBack: () -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel()
) {
    val thread       by viewModel.thread.collectAsState()
    val mediaMessages by viewModel.mediaMessages.collectAsState()
    val context      = LocalContext.current
    val scope        = rememberCoroutineScope()

    // ── Nickname dialog state ──────────────────────────────────────────────────
    var showNicknameDialog by remember { mutableStateOf(false) }
    var nicknameInput      by remember { mutableStateOf("") }

    // ── Accent color dialog state ─────────────────────────────────────────────
    var showAccentColorDialog by remember { mutableStateOf(false) }

    // ── Chat background dialog state ──────────────────────────────────────────
    var showChatBackgroundDialog by remember { mutableStateOf(false) }
    val isDarkTheme = isAppInDarkTheme()

    // ── Full-screen viewer state ───────────────────────────────────────────────
    var fullScreenUri by remember { mutableStateOf<String?>(null) }

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

    // ── Accent color dialog ───────────────────────────────────────────────────
    if (showAccentColorDialog) {
        thread?.let { t ->
            AccentColorDialog(
                currentArgb = t.accentColorArgb,
                onSelect = { argb ->
                    viewModel.setAccentColor(argb)
                    showAccentColorDialog = false
                },
                onDismiss = { showAccentColorDialog = false }
            )
        }
    }

    // ── Chat background dialog ────────────────────────────────────────────────
    if (showChatBackgroundDialog) {
        thread?.let { t ->
            ChatBackgroundDialog(
                currentId = t.chatBackgroundId,
                showFollowGlobal = true,
                isDarkTheme = isDarkTheme,
                onSelect = { id ->
                    viewModel.setChatBackground(id)
                    showChatBackgroundDialog = false
                },
                onDismiss = { showChatBackgroundDialog = false }
            )
        }
    }

    // ── Full-screen image viewer ───────────────────────────────────────────────
    fullScreenUri?.let { uri ->
        ContactFullScreenViewer(uri = uri, onDismiss = { fullScreenUri = null })
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
                    ContactActionsSection(
                        thread              = t,
                        isDarkTheme         = isDarkTheme,
                        onToggleMute        = viewModel::toggleMute,
                        onTogglePin         = viewModel::togglePin,
                        onToggleNotifications = viewModel::toggleNotifications,
                        onOpenColorPicker   = { showAccentColorDialog = true },
                        onOpenChatBackgroundPicker = { showChatBackgroundDialog = true }
                    )
                }
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
            }

            // ── Shared media header ────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text  = "Shared media",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text  = "${mediaMessages.size} items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            // ── Shared media grid (3 columns, chunked into rows) ───────────────
            if (mediaMessages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text  = "No shared media",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Chunk into rows of 3 so they fit inside the LazyColumn without
                // triggering nested-scroll conflicts with LazyVerticalGrid.
                items(mediaMessages.chunked(3)) { rowItems ->
                    MediaGridRow(
                        items        = rowItems,
                        onImageClick = { uri -> fullScreenUri = uri }
                    )
                }
                // Bottom breathing room.
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
 * Mute, Pin, and Notifications — plus the "Conversation color" picker row.
 *
 * Each toggle row uses a leading icon, a label, and a trailing Switch for clarity.
 */
@Composable
private fun ContactActionsSection(
    thread: Thread,
    isDarkTheme: Boolean,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleNotifications: () -> Unit,
    onOpenColorPicker: () -> Unit,
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

    // Conversation color row — opens the swatch-grid picker. Effective accent color is
    // custom if set, else the default hash-derived avatarColor.
    val accentArgb = thread.accentColorArgb
    SettingsRow(
        icon = {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(accentArgb?.let { Color(it) } ?: avatarColor(thread.address), CircleShape)
            )
        },
        title = "Conversation color",
        subtitle = accentArgb?.let { argb -> ContactPalette.colors.firstOrNull { it.argb == argb }?.name ?: "Custom" }
            ?: "Default",
        onClick = onOpenColorPicker
    )

    // Chat background row — opens the preview-tile picker. thread.chatBackgroundId null
    // means "no override" (follow global) — ChatBackgroundDialog distinguishes that from
    // an explicit ChatBackgrounds.None via the subtitle here.
    val backgroundId = thread.chatBackgroundId
    val resolvedBackground = ChatBackgrounds.resolve(backgroundId)
    SettingsRow(
        icon = {
            ChatBackgroundPreview(
                background = resolvedBackground,
                isDarkTheme = isDarkTheme,
                modifier = Modifier.size(width = 28.dp, height = 22.dp)
            )
        },
        title = "Chat background",
        subtitle = if (backgroundId == null) "Default" else resolvedBackground.displayName,
        onClick = onOpenChatBackgroundPicker
    )
}

/**
 * AlertDialog with a swatch grid for picking the thread's accent color: "Default"
 * (clears to null, falls back to the hash-derived avatar color) plus the 12
 * [ContactPalette] presets. The current selection is ringed.
 *
 * @param currentArgb Current accentColorArgb, or null if unset.
 * @param onSelect    Called with the chosen ARGB, or null for "Default".
 * @param onDismiss   Called when the dialog is dismissed without a new selection.
 */
@Composable
private fun AccentColorDialog(
    currentArgb: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conversation color") },
        text = {
            val cells: List<Pair<String, Int?>> =
                listOf("Default" to null) + ContactPalette.colors.map { it.name to it.argb }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cells.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (name, argb) ->
                            ColorSwatch(
                                name     = name,
                                argb     = argb,
                                selected = argb == currentArgb,
                                onClick  = { onSelect(argb) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/**
 * A single swatch cell in [AccentColorDialog]'s grid: a colored circle (or a neutral
 * "no color" circle for the Default cell) with a ring around it when [selected], and
 * the color's name below.
 */
@Composable
private fun ColorSwatch(
    name: String,
    argb: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .then(
                    if (selected)
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                )
                .padding(3.dp)
                .clip(CircleShape)
                .background(argb?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (argb == null) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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

// ── MediaGridRow ──────────────────────────────────────────────────────────────

/**
 * A single row of up to three media thumbnails laid out with equal weights.
 *
 * Images are shown as square Coil thumbnails; video items get a play badge;
 * audio items show a waveform icon placeholder.
 *
 * @param items       Up to 3 [Message] objects that have a non-null [Message.attachmentUri].
 * @param onImageClick Called with the URI when the user taps an image or video thumbnail.
 */
@Composable
private fun MediaGridRow(
    items: List<Message>,
    onImageClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { message ->
            Box(modifier = Modifier.weight(1f)) {
                MediaGridItem(message = message, onImageClick = onImageClick)
            }
        }
        // Fill empty slots in the last row with invisible spacers so items stay left-aligned
        // and don't stretch to fill the row.
        repeat(3 - items.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * A single square thumbnail cell in the shared-media grid.
 *
 * Routing logic:
 *  - image types: Coil thumbnail; tap opens full-screen viewer.
 *  - video types: dark background with a play icon; tap opens full-screen viewer.
 *  - audio types or anything else: dark background with a music-note icon.
 */
@Composable
private fun MediaGridItem(
    message: Message,
    onImageClick: (String) -> Unit
) {
    val uri      = message.attachmentUri ?: return
    val mime     = message.mimeType ?: ""
    val context  = LocalContext.current

    // Square aspect ratio using BoxWithConstraints so the height matches the width.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                // Images and video both open the full-screen viewer.
                if (mime.startsWith("image/") || mime.startsWith("video/")) {
                    onImageClick(uri)
                }
            }
    ) {
        when {
            mime.startsWith("image/") -> {
                // Coil thumbnail — crossfade on load.
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(Uri.parse(uri))
                        .crossfade(true)
                        .build(),
                    contentDescription = "Image attachment",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            }

            mime.startsWith("video/") -> {
                // Dark tile with a centred play icon. Coil's VideoFrameDecoder would give a
                // real thumbnail but requires the coil-video artefact; keep it simple for now.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A1A1A)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Default.PlayCircle,
                        contentDescription = "Video",
                        tint               = Color.White.copy(alpha = 0.8f),
                        modifier           = Modifier.size(36.dp)
                    )
                }
            }

            else -> {
                // Audio or unknown — music-note placeholder.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Default.MusicNote,
                        contentDescription = "Audio attachment",
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier           = Modifier.size(32.dp)
                    )
                }
            }
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
private fun ContactFullScreenViewer(uri: String, onDismiss: () -> Unit) {
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
                Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                    type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                    putExtra(ContactsContract.Intents.Insert.PHONE, address)
                }
            }
            context.startActivity(intent)
        }
    }
}

