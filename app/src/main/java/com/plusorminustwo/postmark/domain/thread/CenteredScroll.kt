package com.plusorminustwo.postmark.domain.thread

/**
 * Scroll offset that vertically centers an item in a `reverseLayout = true` LazyColumn.
 *
 * In a reverse-layout list a POSITIVE `scrollOffset` passed to
 * `animateScrollToItem` / `scrollToItem` shifts the target item UPWARD from the
 * bottom edge (this is the same convention the working date-label scroll relies on).
 * Centering therefore means lifting the item by half of the leftover space:
 * `(viewportHeight - itemSize) / 2`.
 *
 * When the item is taller than (or equal to) the viewport the leftover space is
 * zero or negative; we clamp to 0 so the item's bottom edge simply lands at the
 * viewport bottom rather than over-scrolling.
 *
 * @param viewportHeight the list viewport height in pixels
 * @param itemSize       the target item's measured height in pixels
 * @return a non-negative offset; positive values shift the item up from the bottom edge
 */
fun centeredScrollOffsetReverseLayout(viewportHeight: Int, itemSize: Int): Int =
    ((viewportHeight - itemSize) / 2).coerceAtLeast(0)
