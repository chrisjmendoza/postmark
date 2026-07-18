package com.plusorminustwo.postmark.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily

// ── Brand colors (dark) ──────────────────────────────────────────────────────

private val BgPrimary    = Color(0xFF1C1C1E)
private val BgSecondary  = Color(0xFF2C2C2E)
private val BgTertiary   = Color(0xFF3A3A3C)

private val TextPrimary   = Color(0xFFF5F5F0)
private val TextSecondary = Color(0xFF8E8E93)
private val TextTertiary  = Color(0xFF636366)

private val AccentBlue   = Color(0xFF378ADD)
private val AccentGreen  = Color(0xFF30D158)
private val AccentPurple = Color(0xFFBF5AF2)
private val AccentAmber  = Color(0xFFFF9F0A)

private val ContactBlueBg   = Color(0xFF1A3A5C)
private val ContactGreenBg  = Color(0xFF1A3A2C)
private val ContactPurpleBg = Color(0xFF2C1A3A)

// ── Material 3 dark color scheme ─────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    background          = BgPrimary,
    surface             = BgSecondary,
    surfaceVariant      = BgTertiary,
    onBackground        = TextPrimary,
    onSurface           = TextPrimary,
    onSurfaceVariant    = TextSecondary,
    primary             = AccentBlue,
    onPrimary           = Color(0xFFFFFFFF),
    primaryContainer    = ContactBlueBg,
    onPrimaryContainer  = AccentBlue,
    secondary           = AccentGreen,
    onSecondary         = Color(0xFF000000),
    secondaryContainer  = ContactGreenBg,
    onSecondaryContainer = AccentGreen,
    tertiary            = AccentPurple,
    onTertiary          = Color(0xFFFFFFFF),
    tertiaryContainer   = ContactPurpleBg,
    onTertiaryContainer = AccentPurple,
    outline             = BgTertiary,
    outlineVariant      = BgTertiary,
    error               = Color(0xFFFF453A),
    onError             = Color(0xFFFFFFFF),
    scrim               = Color(0xFF000000),
    inverseSurface      = TextPrimary,
    inverseOnSurface    = BgPrimary,
    inversePrimary      = Color(0xFF0056B3),
)

// ── Material 3 light color scheme ────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    background          = Color(0xFFF2F2F7),
    surface             = Color(0xFFFFFFFF),
    surfaceVariant      = Color(0xFFE5E5EA),
    onBackground        = Color(0xFF1C1C1E),
    onSurface           = Color(0xFF1C1C1E),
    onSurfaceVariant    = Color(0xFF3C3C43),
    primary             = AccentBlue,
    onPrimary           = Color(0xFFFFFFFF),
    primaryContainer    = Color(0xFFD6E8FA),
    onPrimaryContainer  = Color(0xFF0056B3),
    secondary           = AccentGreen,
    onSecondary         = Color(0xFFFFFFFF),
    secondaryContainer  = Color(0xFFD1F5DC),
    onSecondaryContainer = Color(0xFF1A7A3A),
    tertiary            = AccentPurple,
    onTertiary          = Color(0xFFFFFFFF),
    tertiaryContainer   = Color(0xFFEDD6FC),
    onTertiaryContainer = Color(0xFF6B1E8E),
    outline             = Color(0xFFB8B8BD),
    outlineVariant      = Color(0xFFE5E5EA),
    error               = Color(0xFFFF3B30),
    onError             = Color(0xFFFFFFFF),
    inverseSurface      = Color(0xFF1C1C1E),
    inverseOnSurface    = Color(0xFFF5F5F0),
    inversePrimary      = AccentBlue,
)

// ── Extended brand colors ─────────────────────────────────────────────────────

/** Brand-specific colour tokens that extend Material3's colour scheme. */
data class PostmarkColors(
    val sentBubble: Color,
    val receivedBubble: Color,
    val receivedBubbleBorder: Color,
    val textTertiary: Color,
    val accentAmber: Color,
    val accentPurple: Color,
    val contactBlueBg: Color,
    val contactBlueText: Color,
    val contactGreenBg: Color,
    val contactGreenText: Color,
    val contactPurpleBg: Color,
    val contactPurpleText: Color,
)

private val DarkPostmarkColors = PostmarkColors(
    sentBubble            = AccentBlue,
    receivedBubble        = BgSecondary,
    receivedBubbleBorder  = BgTertiary,
    textTertiary          = TextTertiary,
    accentAmber           = AccentAmber,
    accentPurple          = AccentPurple,
    contactBlueBg         = ContactBlueBg,
    contactBlueText       = AccentBlue,
    contactGreenBg        = ContactGreenBg,
    contactGreenText      = AccentGreen,
    contactPurpleBg       = ContactPurpleBg,
    contactPurpleText     = AccentPurple,
)

private val LightPostmarkColors = PostmarkColors(
    sentBubble            = AccentBlue,
    receivedBubble        = Color(0xFFE5E5EA),
    receivedBubbleBorder  = Color(0xFFD1D1D6),
    textTertiary          = Color(0xFF8E8E93),
    accentAmber           = AccentAmber,
    accentPurple          = AccentPurple,
    contactBlueBg         = Color(0xFFD6E8FA),
    contactBlueText       = Color(0xFF0056B3),
    contactGreenBg        = Color(0xFFD1F5DC),
    contactGreenText      = Color(0xFF1A7A3A),
    contactPurpleBg       = Color(0xFFEDD6FC),
    contactPurpleText     = Color(0xFF6B1E8E),
)

val LocalPostmarkColors = staticCompositionLocalOf { DarkPostmarkColors }

// ── Theme entry point ─────────────────────────────────────────────────────────

@Composable
fun PostmarkTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    fontFamilyPreference: FontFamilyPreference = FontFamilyPreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDark = when (themePreference) {
        ThemePreference.ALWAYS_DARK  -> true
        ThemePreference.ALWAYS_LIGHT -> false
        ThemePreference.SYSTEM       -> isSystemInDarkTheme()
    }
    val colorScheme    = if (useDark) DarkColorScheme    else LightColorScheme
    val postmarkColors = if (useDark) DarkPostmarkColors else LightPostmarkColors
    val fontFamily      = fontFamilyPreference.toFontFamilyOrNull()

    CompositionLocalProvider(LocalPostmarkColors provides postmarkColors) {
        if (fontFamily == null) {
            // SYSTEM: no typography override — identical to pre-Phase-D behavior.
            MaterialTheme(
                colorScheme = colorScheme,
                content = content
            )
        } else {
            val typography = remember(fontFamily) { applyFontFamily(Typography(), fontFamily) }
            MaterialTheme(
                colorScheme = colorScheme,
                typography = typography,
                content = content
            )
        }
    }
}

/** True when the resolved app theme is dark — compares background luminance rather than
 *  the raw system setting, so it reflects [ThemePreference.ALWAYS_DARK]/[ThemePreference.ALWAYS_LIGHT]
 *  overrides too, not just SYSTEM. */
@Composable
fun isAppInDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Maps [FontFamilyPreference] to the Compose [FontFamily] it applies; SYSTEM applies
 *  none (the platform default), so callers skip building a custom [Typography]. */
fun FontFamilyPreference.toFontFamilyOrNull(): FontFamily? = when (this) {
    FontFamilyPreference.SYSTEM    -> null
    FontFamilyPreference.SERIF     -> FontFamily.Serif
    FontFamilyPreference.MONOSPACE -> FontFamily.Monospace
}

/** Copies [typography]'s 15 M3 text styles with [fontFamily] applied to each. */
private fun applyFontFamily(typography: Typography, fontFamily: FontFamily): Typography = typography.copy(
    displayLarge   = typography.displayLarge.copy(fontFamily = fontFamily),
    displayMedium  = typography.displayMedium.copy(fontFamily = fontFamily),
    displaySmall   = typography.displaySmall.copy(fontFamily = fontFamily),
    headlineLarge  = typography.headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = typography.headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall  = typography.headlineSmall.copy(fontFamily = fontFamily),
    titleLarge     = typography.titleLarge.copy(fontFamily = fontFamily),
    titleMedium    = typography.titleMedium.copy(fontFamily = fontFamily),
    titleSmall     = typography.titleSmall.copy(fontFamily = fontFamily),
    bodyLarge      = typography.bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium     = typography.bodyMedium.copy(fontFamily = fontFamily),
    bodySmall      = typography.bodySmall.copy(fontFamily = fontFamily),
    labelLarge     = typography.labelLarge.copy(fontFamily = fontFamily),
    labelMedium    = typography.labelMedium.copy(fontFamily = fontFamily),
    labelSmall     = typography.labelSmall.copy(fontFamily = fontFamily),
)
