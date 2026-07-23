package com.plusorminustwo.postmark.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit

/**
 * Scales [TextStyle.fontSize] AND [TextStyle.lineHeight] by the same factor.
 *
 * Bubble text size (pinch-to-zoom) and the Appearance preview both used to scale
 * only `fontSize`, leaving the base style's `lineHeight` (e.g. bodyMedium's fixed
 * 20sp) untouched. At high scale — especially compounded with a large system
 * font size — the now-larger glyphs no longer fit the old line height and
 * multi-line text overlaps vertically. Scaling both by the same factor keeps
 * the font-size/line-height ratio (and therefore line spacing) constant.
 *
 * `TextUnit.Unspecified` is left alone rather than multiplied — scaling an
 * unspecified unit would produce a NaN-based TextUnit, not stay unspecified.
 */
fun TextStyle.withBubbleScale(scale: Float): TextStyle = copy(
    fontSize = if (fontSize == TextUnit.Unspecified) fontSize else fontSize * scale,
    lineHeight = if (lineHeight == TextUnit.Unspecified) lineHeight else lineHeight * scale
)
