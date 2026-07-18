package com.plusorminustwo.postmark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.plusorminustwo.postmark.domain.customization.ChatBackground
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds

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
 * @param currentId        Currently selected id: null for "Default"/unset, otherwise
 *                          a [ChatBackground.id] (including [ChatBackgrounds.None]'s "none").
 * @param showFollowGlobal Whether to show the leading "Default" (follow global) tile.
 * @param isDarkTheme       Selects which theme variant of each background to preview.
 * @param onSelect          Called with the chosen id: null for "Default", or a catalog id.
 * @param onDismiss         Called when the dialog is dismissed without a new selection.
 */
@Composable
fun ChatBackgroundDialog(
    currentId: String?,
    showFollowGlobal: Boolean,
    isDarkTheme: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat background") },
        text = {
            val cells: List<Pair<String, String?>> =
                (if (showFollowGlobal) listOf("Default" to null) else emptyList()) +
                    ChatBackgrounds.all.map { it.displayName to it.id }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cells.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (name, id) ->
                            BackgroundTile(
                                name = name,
                                background = id?.let { ChatBackgrounds.resolve(it) },
                                isDarkTheme = isDarkTheme,
                                selected = id == currentId,
                                onClick = { onSelect(id) },
                                modifier = Modifier.weight(1f)
                            )
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
