package com.plusorminustwo.postmark.ui.thread

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Returns the date in [activeDates] closest to [target] by number of days.
 * If two dates are equidistant, the earlier one is returned (minByOrNull tiebreaker).
 * Returns null if [activeDates] is empty.
 */
fun findNearestActiveDate(target: LocalDate, activeDates: Set<LocalDate>): LocalDate? {
    if (activeDates.isEmpty()) return null
    return activeDates.minByOrNull { abs(ChronoUnit.DAYS.between(it, target)) }
}

/**
 * Computes the `scrollOffset` to pass to `LazyListState.scrollToItem` so that the
 * target item appears at the **top** of a `reverseLayout = true` [LazyColumn] instead
 * of the default bottom-aligned position.
 *
 * With `reverseLayout = true`, `scrollToItem(index, 0)` aligns the item to the leading
 * edge (visual bottom). A positive `scrollOffset` shifts the item upward by that many
 * pixels. Setting `offset = viewportHeight - itemHeight` places the item's top flush
 * with the viewport top.
 *
 * @param viewportHeight  Visible viewport height in pixels
 *                        (`LazyListLayoutInfo.viewportSize.height`).
 * @param itemHeightPx    Height of the target item in pixels.
 * @return Scroll offset in pixels (always ≥ 0).
 */
fun scrollOffsetToAlignTop(viewportHeight: Int, itemHeightPx: Int): Int =
    (viewportHeight - itemHeightPx).coerceAtLeast(0)
