package com.plusorminustwo.postmark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val AVATAR_COLORS = listOf(
    Color(0xFF5C6BC0), // indigo
    Color(0xFF26A69A), // teal
    Color(0xFFEF5350), // red
    Color(0xFFAB47BC), // purple
    Color(0xFF42A5F5), // blue
    Color(0xFFFF7043), // deep orange
    Color(0xFF66BB6A), // green
    Color(0xFFEC407A), // pink
)

fun avatarColor(seed: String): Color {
    val idx = Math.abs(seed.hashCode()) % AVATAR_COLORS.size
    return AVATAR_COLORS[idx]
}

/**
 * @param overrideColor When non-null, used as the avatar background instead of the
 *                       hash-derived [avatarColor] — the per-contact accent color
 *                       (Thread.accentColorArgb), when the caller has a Thread in hand.
 */
@Composable
fun LetterAvatar(name: String, colorSeed: String = name, size: Dp = 44.dp, overrideColor: Color? = null) {
    val letter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val bg = overrideColor ?: avatarColor(colorSeed)
    // Convert dp → sp through density only (no fontScale) so the letter's visual
    // size stays tied to the fixed-dp circle instead of growing with system font
    // size and clipping — the circle itself doesn't grow with fontScale, so the
    // text inside it can't either.
    val letterFontSize = with(LocalDensity.current) { (size * 0.4f).toSp() }
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            fontSize = letterFontSize,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}
