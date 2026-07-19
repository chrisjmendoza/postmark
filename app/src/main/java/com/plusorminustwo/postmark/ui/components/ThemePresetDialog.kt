package com.plusorminustwo.postmark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds
import com.plusorminustwo.postmark.domain.customization.ColorMath
import com.plusorminustwo.postmark.domain.customization.ContactPalette
import com.plusorminustwo.postmark.domain.customization.ThemePreset
import com.plusorminustwo.postmark.domain.customization.ThemePresets

// ── ThemePresetDialog ─────────────────────────────────────────────────────────

/**
 * AlertDialog listing the built-in [ThemePresets] as tappable preview cards, each showing
 * a mini received bubble (contact color) + sent bubble (sent color) over the preset's
 * chat-background gradient in the current theme variant ([isDarkTheme]). Tapping a card
 * calls [onPresetSelected] — the host applies all three look fields through its existing
 * per-thread setters (accent / sent / background), copying the values. Mirrors the visual
 * language of [AccentColorDialog] / [ChatBackgroundDialog] (chunked grid, one trailing
 * button), sharing [ContactPalette.onBubbleContentColor] for legible bubble content.
 *
 * @param isDarkTheme      Selects which theme variant of each background to preview
 *                          (host passes [com.plusorminustwo.postmark.ui.theme.isAppInDarkTheme]).
 * @param onPresetSelected Called with the tapped preset; the host applies + dismisses.
 * @param onDismiss        Called when the dialog is dismissed without a selection.
 */
@Composable
fun ThemePresetDialog(
    isDarkTheme: Boolean,
    onPresetSelected: (ThemePreset) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme preset") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Ready-made color combos. Tap one to apply it to this conversation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ThemePresets.all.chunked(2).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowPresets.forEach { preset ->
                            ThemePresetCard(
                                preset = preset,
                                isDarkTheme = isDarkTheme,
                                onClick = { onPresetSelected(preset) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(2 - rowPresets.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** One preset cell: the mini chat preview above the preset's name. */
@Composable
private fun ThemePresetCard(
    preset: ThemePreset,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ThemePresetPreview(
            preset = preset,
            isDarkTheme = isDarkTheme,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = preset.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The mini chat preview: a received bubble (contact color, start-aligned) and a sent
 * bubble (sent color, end-aligned) over the preset's background gradient. Each bubble
 * container is first guarded away from the gradient's stops via
 * [ColorMath.adjustAccentForBackgroundStops] — the identical nudge the thread applies —
 * so a nudged preset previews the color it will really render. A null background (empty
 * catalog stops) falls back to the plain theme surface (and the guard is the identity),
 * matching [ChatBackgroundPreview]'s None rendering.
 */
@Composable
private fun ThemePresetPreview(
    preset: ThemePreset,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val background = ChatBackgrounds.resolve(preset.backgroundId)
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val stops = if (isDarkTheme) background.darkColorsArgb else background.lightColorsArgb
    val brush = remember(preset.backgroundId, isDarkTheme, surface) {
        if (stops.isEmpty()) Brush.verticalGradient(listOf(surface, surface))
        else Brush.verticalGradient(stops.map { Color(it) })
    }
    // Match the thread exactly: the conversation guards each bubble container away from
    // the background gradient's stops (empty stops → identity), so a nudged preset
    // (e.g. Sunset's violet sent bubble on Deep Plum, dark) previews the SAME lightened
    // color the thread actually renders — not the raw preset color, which would blend into
    // the gradient here and mislead. Presets with no background pass through unchanged.
    val contactContainer = ColorMath.adjustAccentForBackgroundStops(preset.contactArgb, stops)
    val sentContainer = ColorMath.adjustAccentForBackgroundStops(preset.sentArgb, stops)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(brush)
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
        ) {
            PreviewBubble(containerArgb = contactContainer, isDark = isDarkTheme, sent = false)
            PreviewBubble(containerArgb = sentContainer, isDark = isDarkTheme, sent = true)
        }
    }
}

/**
 * One preview bubble: a rounded chip in [containerArgb] holding two short "text" bars in
 * its [ContactPalette.onBubbleContentColor], aligned to the end for [sent] messages and to
 * the start for received ones.
 */
@Composable
private fun PreviewBubble(containerArgb: Int, isDark: Boolean, sent: Boolean) {
    val content = Color(ContactPalette.onBubbleContentColor(containerArgb, isDark))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (sent) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(containerArgb))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            TextBar(color = content, widthFraction = 1f)
            TextBar(color = content, widthFraction = 0.6f)
        }
    }
}

/** A single faux line of text inside a [PreviewBubble]. */
@Composable
private fun TextBar(color: Color, widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = 0.9f))
    )
}
