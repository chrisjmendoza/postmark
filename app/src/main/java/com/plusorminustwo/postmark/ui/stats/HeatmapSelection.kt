package com.plusorminustwo.postmark.ui.stats

import java.time.LocalDate

/**
 * Pure state machine for heatmap day selection.
 *
 * Gesture vocabulary:
 * - **Tap** selects just that day (tapping the sole selected day again clears it).
 * - **Long-press** enters multi-select. A single already-selected day acts as the
 *   anchor, so *tap the first day, long-press the last* selects the whole range
 *   between them (shift-click semantics).
 * - **In multi-select**, taps toggle individual days, and each long-press extends
 *   the selection from the last long-pressed day (the [anchor]) to the pressed
 *   day, inclusive. Deselecting the last remaining day exits multi-select.
 */
data class HeatmapSelection(
    val days: Set<LocalDate> = emptySet(),
    val multiSelect: Boolean = false,
    val anchor: LocalDate? = null
) {
    fun tap(date: LocalDate): HeatmapSelection = when {
        !multiSelect ->
            if (days == setOf(date)) HeatmapSelection() else HeatmapSelection(days = setOf(date))
        date in days -> {
            val remaining = days - date
            if (remaining.isEmpty()) HeatmapSelection()
            else copy(days = remaining, anchor = anchor.takeIf { it != date })
        }
        else -> copy(days = days + date)
    }

    fun longPress(date: LocalDate): HeatmapSelection {
        // In single mode the sole selected day is the implicit anchor; in multi mode
        // the anchor is wherever the last long-press landed.
        val from = if (multiSelect) anchor else days.singleOrNull()
        val added = if (from != null) rangeBetween(from, date) else setOf(date)
        return HeatmapSelection(days = days + added, multiSelect = true, anchor = date)
    }

    private fun rangeBetween(a: LocalDate, b: LocalDate): Set<LocalDate> {
        val (start, end) = if (a <= b) a to b else b to a
        return generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.toSet()
    }
}
