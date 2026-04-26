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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
fun LetterAvatar(name: String, size: Dp = 44.dp) {
    val letter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val bg = avatarColor(name)
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}
