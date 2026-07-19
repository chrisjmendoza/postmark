package com.plusorminustwo.postmark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.plusorminustwo.postmark.domain.customization.ChatBackground
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds
import java.io.File

/**
 * Small rounded-rect swatch previewing [background]'s gradient in the current theme
 * variant ([isDarkTheme]). [ChatBackgrounds.None] renders a plain neutral fill.
 *
 * Shared by [ChatBackgroundDialog]'s tiles and the "Chat background" row preview in
 * ContactDetailScreen / SettingsScreen, so the row and the dialog always agree on how
 * a given background actually looks.
 */
@Composable
fun ChatBackgroundPreview(background: ChatBackground, isDarkTheme: Boolean, modifier: Modifier = Modifier) {
    val neutral = MaterialTheme.colorScheme.surfaceVariant
    val brush = remember(background.id, isDarkTheme) {
        if (background == ChatBackgrounds.None) {
            Brush.verticalGradient(listOf(neutral, neutral))
        } else {
            Brush.verticalGradient(
                (if (isDarkTheme) background.darkColorsArgb else background.lightColorsArgb).map { Color(it) }
            )
        }
    }
    Box(modifier = modifier.clip(RoundedCornerShape(6.dp)).background(brush))
}

/**
 * Preview-tile picker for a chat background (Phase C customization). Shared by
 * [com.plusorminustwo.postmark.ui.contact.ContactDetailScreen] (per-thread override —
 * [showFollowGlobal] = true, adds a leading "Default" tile) and
 * [com.plusorminustwo.postmark.ui.settings.SettingsScreen] (global default —
 * [showFollowGlobal] = false, just [ChatBackgrounds.all]).
 *
 * @param currentId        Currently selected id: null for "Default"/unset, a
 *                          [ChatBackground.id] (including [ChatBackgrounds.None]'s "none"),
 *                          or a custom `image:<fileName>` id.
 * @param showFollowGlobal Whether to show the leading "Default" (follow global) tile.
 * @param isDarkTheme       Selects which theme variant of each background to preview.
 * @param currentImageFile When [currentId] is a custom-image id, its resolved file (for the
 *                          "Custom image" tile's thumbnail); null otherwise. The host
 *                          resolves it via its ViewModel so this composable stays store-free.
 * @param onSelect          Called with the chosen id: null for "Default", or a catalog id.
 * @param onPickImage       Called when the user taps "From gallery" — the host launches the
 *                          photo picker and, on a result, begins the placement flow. The
 *                          dialog sets nothing.
 * @param onCurrentImageOptions Called when the user taps the current custom-image tile — the
 *                          host opens the "Background photo" options (adjust placement / choose
 *                          a different photo). Only reachable while an image is selected.
 * @param onDismiss         Called when the dialog is dismissed without a new selection.
 */
@Composable
fun ChatBackgroundDialog(
    currentId: String?,
    showFollowGlobal: Boolean,
    isDarkTheme: Boolean,
    currentImageFile: File? = null,
    onSelect: (String?) -> Unit,
    onPickImage: () -> Unit = {},
    onCurrentImageOptions: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat background") },
        text = {
            val tiles: List<ChatBgTile> = buildList {
                if (showFollowGlobal) add(ChatBgTile.Preset("Default", null))
                ChatBackgrounds.all.forEach { add(ChatBgTile.Preset(it.displayName, it.id)) }
                // Selected custom image gets its own ringed tile so it reads as chosen —
                // no catalog entry matches an "image:" id (resolve() returns None for it).
                if (ChatBackgrounds.isImageId(currentId)) add(ChatBgTile.CustomImage(currentImageFile))
                add(ChatBgTile.Gallery)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { tile ->
                            when (tile) {
                                is ChatBgTile.Preset -> BackgroundTile(
                                    name = tile.name,
                                    background = tile.id?.let { ChatBackgrounds.resolve(it) },
                                    isDarkTheme = isDarkTheme,
                                    selected = tile.id == currentId,
                                    onClick = { onSelect(tile.id) },
                                    modifier = Modifier.weight(1f)
                                )
                                is ChatBgTile.CustomImage -> CustomImageTile(
                                    file = tile.file,
                                    onClick = onCurrentImageOptions,
                                    modifier = Modifier.weight(1f)
                                )
                                ChatBgTile.Gallery -> GalleryTile(
                                    onClick = onPickImage,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/** Grid cells rendered by [ChatBackgroundDialog]. */
private sealed interface ChatBgTile {
    data class Preset(val name: String, val id: String?) : ChatBgTile
    data class CustomImage(val file: File?) : ChatBgTile
    data object Gallery : ChatBgTile
}

/**
 * A single tile in [ChatBackgroundDialog]'s grid: [background] null renders the
 * "Default" (follow global) tile with a palette icon; [ChatBackgrounds.None] renders
 * a plain neutral tile with a "no background" icon; any other entry renders its
 * gradient via [ChatBackgroundPreview].
 */
@Composable
private fun BackgroundTile(
    name: String,
    background: ChatBackground?,
    isDarkTheme: Boolean,
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
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .then(
                    if (selected)
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    else Modifier
                )
                .padding(3.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            ChatBackgroundPreview(
                background = background ?: ChatBackgrounds.None,
                isDarkTheme = isDarkTheme,
                modifier = Modifier.fillMaxSize()
            )
            when (background) {
                null -> Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                ChatBackgrounds.None -> Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                else -> Unit
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
 * Ringed "Custom image" tile shown when the current selection is a custom-image id.
 * Renders [file]'s thumbnail via Coil when present, else a generic image icon (e.g. the
 * file went missing after a restore). Tapping opens the "Background photo" options (adjust
 * placement / choose a different photo).
 */
@Composable
private fun CustomImageTile(
    file: File?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (file != null) {
                val ctx = LocalContext.current
                AsyncImage(
                    model = remember(file) { ImageRequest.Builder(ctx).data(file).crossfade(true).build() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = ChatBackgrounds.CUSTOM_IMAGE_LABEL,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Small thumbnail of a custom-image chat background for a "Chat background" row's icon
 * slot. Renders [file] via Coil when present, else a neutral tile with an image icon (the
 * file went missing — e.g. after a restore on a new device). Shared by ContactDetailScreen
 * and AppearanceScreen (the row-level counterpart to [CustomImageTile]).
 */
@Composable
fun ChatBackgroundThumbnail(file: File?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (file != null) {
            val ctx = LocalContext.current
            AsyncImage(
                model = remember(file) { ImageRequest.Builder(ctx).data(file).crossfade(true).build() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** "From gallery" action tile — invokes [onClick] to launch the photo picker; sets nothing. */
@Composable
private fun GalleryTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .padding(3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AddPhotoAlternate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "From gallery",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
