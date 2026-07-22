package com.plusorminustwo.postmark.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plusorminustwo.postmark.domain.customization.ColorMath
import com.plusorminustwo.postmark.domain.customization.ContactPalette

/**
 * Free-form HSV color picker (Phase H of user customization): a saturation/value
 * panel for the current hue, a hue slider, a hex text field, and a live preview.
 * Opened from [com.plusorminustwo.postmark.ui.contact.ContactDetailScreen]'s
 * "Custom…" swatch tile.
 *
 * All state (hue/saturation/value, the hex field text) is local via [remember] and
 * only reaches the caller through [onApply] — dismissing or cancelling discards it,
 * matching [AlertDialog]'s usual confirm/cancel contract elsewhere in this app.
 *
 * @param initialArgb Starting color (opaque packed ARGB).
 * @param onApply     Called with the picked ARGB when the user taps "Apply".
 * @param onDismiss   Called when the dialog is dismissed without applying (Cancel,
 *                    scrim tap, back press).
 */
@Composable
fun HsvColorPickerDialog(
    initialArgb: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val initialHsv = remember(initialArgb) { ColorMath.argbToHsv(initialArgb) }
    var hue by remember { mutableStateOf(initialHsv.first) }
    var saturation by remember { mutableStateOf(initialHsv.second) }
    var value by remember { mutableStateOf(initialHsv.third) }
    val candidateArgb = ColorMath.hsvToArgb(hue, saturation, value)

    var hexText by remember { mutableStateOf(ColorMath.formatHexColor(candidateArgb)) }
    var hexError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SaturationValuePanel(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onChange = { s, v ->
                        saturation = s
                        value = v
                        hexText = ColorMath.formatHexColor(ColorMath.hsvToArgb(hue, s, v))
                        hexError = false
                    }
                )
                HueSlider(
                    hue = hue,
                    onChange = { h ->
                        hue = h
                        hexText = ColorMath.formatHexColor(ColorMath.hsvToArgb(h, saturation, value))
                        hexError = false
                    }
                )
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text
                        val parsed = ColorMath.parseHexColor(text)
                        if (parsed != null) {
                            val (h, s, v) = ColorMath.argbToHsv(parsed)
                            hue = h
                            saturation = s
                            value = v
                            hexError = false
                        } else {
                            hexError = true
                        }
                    },
                    label = { Text("Hex") },
                    isError = hexError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ColorPreviewRow(argb = candidateArgb)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(candidateArgb) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── SaturationValuePanel ─────────────────────────────────────────────────────────

/**
 * 2D saturation (horizontal, 0..1 left-to-right) / value (vertical, 0..1
 * bottom-to-top) picker for the given [hue]. Drawn as a white-to-hue horizontal
 * gradient overlaid with a transparent-to-black vertical gradient — the standard
 * HSV picker construction (top-left = white, top-right = pure hue, bottom = black
 * regardless of saturation).
 */
@Composable
private fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (saturation: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hueColor = remember(hue) { Color(ColorMath.hsvToArgb(hue, 1f, 1f)) }
    // Both gradients are hoisted out of the draw scope so they're not reallocated on every
    // frame: the saturation ramp only changes with the hue, and the value ramp is constant.
    val saturationGradient = remember(hueColor) { Brush.horizontalGradient(listOf(Color.White, hueColor)) }
    val valueGradient = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black)) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            // Tap-to-jump and drag-to-follow are independent gesture detectors on
            // separate pointerInput modifiers (mirrors ThreadScreen's WaveformScrubber)
            // rather than trying to unify them under one detector.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange(
                        (offset.x / size.width).coerceIn(0f, 1f),
                        1f - (offset.y / size.height).coerceIn(0f, 1f)
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    )
                }
            }
    ) {
        drawRect(saturationGradient)
        drawRect(valueGradient)

        val thumbCenter = Offset(saturation * size.width, (1f - value) * size.height)
        drawCircle(Color.White, radius = 9.dp.toPx(), center = thumbCenter, style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = 9.dp.toPx(), center = thumbCenter, style = Stroke(width = 1.dp.toPx()))
    }
}

// ── HueSlider ─────────────────────────────────────────────────────────────────────

/** Horizontal hue spectrum (0..360°) slider with a circular thumb marker. */
@Composable
private fun HueSlider(
    hue: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // 7 stops (0/60/.../360) — a full-saturation, full-value rainbow sweep.
    val spectrum = remember {
        (0..360 step 60).map { Color(ColorMath.hsvToArgb(it.toFloat(), 1f, 1f)) }
    }
    // Hoisted out of the draw scope — a constant 7-stop rainbow, no need to rebuild per frame.
    val spectrumBrush = remember { Brush.horizontalGradient(spectrum) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange(((offset.x / size.width) * 360f).coerceIn(0f, 360f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(((change.position.x / size.width) * 360f).coerceIn(0f, 360f))
                }
            }
    ) {
        drawRect(spectrumBrush)

        val thumbCenter = Offset((hue / 360f) * size.width, size.height / 2f)
        val thumbRadius = size.height / 2f
        drawCircle(Color.White, radius = thumbRadius, center = thumbCenter, style = Stroke(width = 3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = thumbRadius, center = thumbCenter, style = Stroke(width = 1.dp.toPx()))
    }
}

// ── ColorPreviewRow ───────────────────────────────────────────────────────────────

/**
 * Live preview of the candidate color as a filled, bubble-like rounded rect with
 * "Aa" rendered in the same white/black-by-contrast content color a real bubble
 * would use ([ContactPalette.onBubbleContentColor]) — so the user sees the actual
 * text contrast they'll get, not just the raw swatch.
 */
@Composable
private fun ColorPreviewRow(argb: Int, modifier: Modifier = Modifier) {
    val contentArgb = remember(argb) { ContactPalette.onBubbleContentColor(argb, isDark = false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(argb)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Aa",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color(contentArgb)
        )
    }
}
