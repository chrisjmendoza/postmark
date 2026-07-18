package com.plusorminustwo.postmark.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.plusorminustwo.postmark.domain.customization.ColorMath
import com.plusorminustwo.postmark.domain.customization.ContactPalette

// ── Brand colors (dark) ──────────────────────────────────────────────────────

private val BgPrimary    = Color(0xFF1C1C1E)
private val BgSecondary  = Color(0xFF2C2C2E)
private val BgTertiary   = Color(0xFF3A3A3C)

// Light theme's background constant, extracted (rather than left inline in
// LightColorScheme below) so applyAppAccent can look up "the OTHER theme's
// background" for inversePrimary without duplicating the literal.
private val LightBgPrimary = Color(0xFFF2F2F7)

private val TextPrimary   = Color(0xFFF5F5F0)
private val TextSecondary = Color(0xFF8E8E93)

private val AccentBlue   = Color(0xFF378ADD)
private val AccentGreen  = Color(0xFF30D158)
private val AccentPurple = Color(0xFFBF5AF2)

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
    background          = LightBgPrimary,
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

// ── Theme entry point ─────────────────────────────────────────────────────────

@Composable
fun PostmarkTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    fontFamilyPreference: FontFamilyPreference = FontFamilyPreference.SYSTEM,
    useDynamicColor: Boolean = false,
    appAccentArgb: Int? = null,
    content: @Composable () -> Unit
) {
    val useDark = when (themePreference) {
        ThemePreference.ALWAYS_DARK  -> true
        ThemePreference.ALWAYS_LIGHT -> false
        ThemePreference.SYSTEM       -> isSystemInDarkTheme()
    }
    val useDynamic = shouldUseDynamicColor(useDynamicColor, Build.VERSION.SDK_INT)
    val baseColorScheme = if (useDynamic) {
        val context = LocalContext.current
        if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (useDark) DarkColorScheme else LightColorScheme
    }
    // Phase I: a global app accent overrides only the primary color family, and only
    // when Material You isn't already driving the palette — Phase G's dynamic color
    // wins outright (see shouldUseDynamicColor's doc). remember mirrors the Phase D
    // typography rebuild below: applyAppAccent's legibility walk is cheap but there's
    // no reason to redo it every recomposition when its inputs haven't changed.
    val colorScheme = if (!useDynamic && appAccentArgb != null) {
        remember(baseColorScheme, appAccentArgb, useDark) {
            applyAppAccent(baseColorScheme, appAccentArgb, useDark)
        }
    } else {
        baseColorScheme
    }
    val fontFamily = fontFamilyPreference.toFontFamilyOrNull()

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

/** Pure decision for whether [PostmarkTheme] should build a dynamic (wallpaper-derived)
 *  color scheme: the user must have opted in AND the device must be API 31+ (Android 12),
 *  the minimum SDK level `dynamicDarkColorScheme`/`dynamicLightColorScheme` support. This
 *  is the single gate — [PostmarkTheme] has no other SDK_INT check for dynamic color. */
fun shouldUseDynamicColor(enabled: Boolean, sdkInt: Int): Boolean =
    enabled && sdkInt >= Build.VERSION_CODES.S

/**
 * Overrides only the primary color family on [scheme] with [accentArgb] — the Phase I
 * global app accent. Mirrors [ContactPalette]'s sent-bubble derivation (vivid
 * container, white/black-by-contrast content) so an app-accent-tinted sent bubble
 * with no per-thread override reads exactly like any other accented bubble. Every
 * other role on [scheme] — secondary/tertiary/background/surface/error/etc. — passes
 * through unchanged. Callers are responsible for the Material You gate (this function
 * has no dynamic-color awareness of its own — see [shouldUseDynamicColor]).
 *
 * - `primary`/`primaryContainer` become [accentArgb], first guarded through
 *   [ColorMath.adjustAccentForBackground] against [scheme]'s own `background` so a
 *   pathological accent (e.g. one that matches the background almost exactly) never
 *   visually vanishes.
 * - `onPrimary`/`onPrimaryContainer` become white or black, whichever contrasts that
 *   (possibly-adjusted) accent more, via [ContactPalette.onBubbleContentColor].
 * - `inversePrimary` nudges the ORIGINAL, unadjusted [accentArgb] against the OTHER
 *   theme's background constant — inversePrimary's whole purpose is rendering on the
 *   opposite theme's surfaces (e.g. a Snackbar action button), so it needs its own
 *   guard against that other background, not the current scheme's.
 */
fun applyAppAccent(scheme: ColorScheme, accentArgb: Int, isDark: Boolean): ColorScheme {
    val pair = ContactPalette.deriveAccentPair(accentArgb, scheme.background.toArgb(), isDark)
    val accent = Color(pair.containerArgb)
    val onAccent = Color(pair.contentArgb)
    val inverseBackgroundArgb = (if (isDark) LightBgPrimary else BgPrimary).toArgb()
    val inversePrimary = Color(ColorMath.adjustAccentForBackground(accentArgb, inverseBackgroundArgb))
    return scheme.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent,
        onPrimaryContainer = onAccent,
        inversePrimary = inversePrimary
    )
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
