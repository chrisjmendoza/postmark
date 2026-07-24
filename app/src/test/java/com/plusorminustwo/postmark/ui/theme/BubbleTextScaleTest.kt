package com.plusorminustwo.postmark.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `withBubbleScale` scales fontSize AND lineHeight by the same factor so a
 * pinch-scaled bubble (or the Appearance preview) keeps its font-size/line-height
 * ratio — the bug this fixes was fontSize scaling alone, leaving a stale
 * lineHeight that made multi-line text overlap at high scale.
 */
class BubbleTextScaleTest {

    @Test
    fun `scales fontSize and lineHeight by the same factor`() {
        val base = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

        val scaled = base.withBubbleScale(1.6f)

        assertEquals(14.sp * 1.6f, scaled.fontSize)
        assertEquals(20.sp * 1.6f, scaled.lineHeight)
    }

    @Test
    fun `preserves the fontSize to lineHeight ratio at any scale`() {
        val base = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
        val baseRatio = base.lineHeight.value / base.fontSize.value

        listOf(0.8f, 1.0f, 1.3f, 1.6f).forEach { scale ->
            val scaled = base.withBubbleScale(scale)
            val scaledRatio = scaled.lineHeight.value / scaled.fontSize.value
            assertEquals(baseRatio, scaledRatio, 0.0001f)
        }
    }

    @Test
    fun `leaves an unspecified lineHeight unspecified rather than producing NaN`() {
        val base = TextStyle(fontSize = 14.sp, lineHeight = TextUnit.Unspecified)

        val scaled = base.withBubbleScale(1.6f)

        assertEquals(TextUnit.Unspecified, scaled.lineHeight)
        assertEquals(14.sp * 1.6f, scaled.fontSize)
    }

    @Test
    fun `leaves an unspecified fontSize unspecified rather than producing NaN`() {
        val base = TextStyle(fontSize = TextUnit.Unspecified, lineHeight = 20.sp)

        val scaled = base.withBubbleScale(1.6f)

        assertEquals(TextUnit.Unspecified, scaled.fontSize)
        assertTrue(scaled.lineHeight.isSp)
    }

    @Test
    fun `scale of 1 is a no-op`() {
        val base = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

        val scaled = base.withBubbleScale(1f)

        assertEquals(base.fontSize, scaled.fontSize)
        assertEquals(base.lineHeight, scaled.lineHeight)
    }
}
