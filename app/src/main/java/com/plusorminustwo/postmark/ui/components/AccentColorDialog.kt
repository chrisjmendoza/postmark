package com.plusorminustwo.postmark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.plusorminustwo.postmark.domain.customization.ColorMath
import com.plusorminustwo.postmark.domain.customization.ContactPalette

// ── AccentColorDialog ────────────────────────────────────────────────────────

/**
 * AlertDialog with a swatch grid for picking a color: "Default" (clears to null,
 * falls back to whatever [defaultHint] describes) plus the 12 [ContactPalette]
 * presets plus a "Custom…" tile opening [HsvColorPickerDialog]. The current selection
 * is ringed — the Custom tile rings when [currentArgb] is set but matches none of the
 * 12 presets. Originally introduced in `ContactDetailScreen` for the "Their color" /
 * "Your bubble color" rows (Phase B/FB2/H of user customization); moved here (Phase I)
 * so [com.plusorminustwo.postmark.ui.settings.AppearanceScreen]'s global app accent
 * row can share it too rather than forking a second copy.
 *
 * @param title       Dialog title — names which color is being picked.
 * @param defaultHint Short description of what "Default" falls back to for this picker.
 * @param currentArgb Current color, or null if unset.
 * @param onSelect    Called with the chosen ARGB, or null for "Default".
 * @param onDismiss   Called when the dialog is dismissed without a new selection.
 */
@Composable
fun AccentColorDialog(
    title: String,
    defaultHint: String,
    currentArgb: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    // Custom (non-preset, non-null) colors ring the "Custom…" tile instead of any
    // preset cell — none of the preset argb comparisons below match a custom value,
    // so no extra logic is needed on their side, only this flag for the new tile.
    val isCustomCurrent = currentArgb != null && ContactPalette.colors.none { it.argb == currentArgb }
    var showCustomPicker by remember { mutableStateOf(false) }

    if (showCustomPicker) {
        HsvColorPickerDialog(
            initialArgb = currentArgb?.takeIf { isCustomCurrent } ?: ContactPalette.colors.first().argb,
            onApply = { argb ->
                onSelect(argb)
                showCustomPicker = false
            },
            onDismiss = { showCustomPicker = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // "Custom…" is appended as the grid's last cell, sharing the same 4-wide
            // chunking as Default + the 12 presets before it (isCustom = true is the
            // only cell without a fixed argb — it opens the HSV picker instead).
            val cells: List<SwatchCell> =
                listOf(SwatchCell("Default", null)) +
                    ContactPalette.colors.map { SwatchCell(it.name, it.argb) } +
                    SwatchCell("Custom", null, isCustom = true)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text  = defaultHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                cells.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { cell ->
                            if (cell.isCustom) {
                                CustomColorTile(
                                    selected = isCustomCurrent,
                                    onClick  = { showCustomPicker = true },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                ColorSwatch(
                                    name     = cell.name,
                                    argb     = cell.argb,
                                    selected = cell.argb == currentArgb,
                                    onClick  = { onSelect(cell.argb) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
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
 * Subtitle describing an accent selection made through [AccentColorDialog]: [unsetText]
 * when [argb] is null, the matching [ContactPalette] preset's name when [argb] equals one,
 * else its "#RRGGBB" hex (a Phase H custom pick has no preset name). Shared by
 * ContactDetail's "Their color"/"Your bubble color" rows (which pass their own default
 * hint as [unsetText]) and Appearance's "App accent color" row (which passes "Default"),
 * so all three describe a color the same way.
 */
fun accentSubtitle(argb: Int?, unsetText: String): String = when {
    argb == null -> unsetText
    else -> ContactPalette.colors.firstOrNull { it.argb == argb }?.name ?: ColorMath.formatHexColor(argb)
}

/** One cell in [AccentColorDialog]'s grid: a named color (Default/preset) or the Custom tile. */
private data class SwatchCell(val name: String, val argb: Int?, val isCustom: Boolean = false)

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
 * The "Custom…" cell in [AccentColorDialog]'s grid: a rainbow-sweep circle (the
 * same hue spectrum as [HsvColorPickerDialog]'s hue slider) with a "+" glyph,
 * opening the free-form HSV picker. Rings like any other swatch when [selected]
 * (the current color is a custom, non-preset pick).
 */
@Composable
private fun CustomColorTile(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rainbow = remember {
        (0..360 step 60).map { Color(ColorMath.hsvToArgb(it.toFloat(), 1f, 1f)) }
    }
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
                .background(Brush.sweepGradient(rainbow)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Custom",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
