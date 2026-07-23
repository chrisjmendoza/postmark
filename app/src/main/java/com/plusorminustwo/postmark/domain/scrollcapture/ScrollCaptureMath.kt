package com.plusorminustwo.postmark.domain.scrollcapture

/**
 * Pure coordinate math for the thread view's custom scroll-capture (long screenshot)
 * support. Kept free of Android types so the reversed-list sign convention — the one
 * piece stock Compose ScrollCapture does not handle — is unit-testable.
 *
 * Capture-space: the platform requests tiles as rectangles whose Y is measured DOWNWARD
 * from the top of the scroll bounds AS THEY WERE when the capture session started.
 * `captureTop` can be negative (content above the start viewport) or run past the
 * viewport bottom (content below). Our job is to translate that into a [LazyListState]
 * scroll delta.
 */
object ScrollCaptureMath {

    /**
     * Content-space scroll delta, in pixels, needed to bring the requested capture band's
     * top edge to the top of the viewport — measured relative to the session's start
     * position (caller resets to the start position first, then applies this delta).
     *
     * In a normal top-down list a positive `captureTop` (content below) is a positive
     * forward scroll. In a `reverseLayout = true` list the scroll axis is inverted, so the
     * same downward request becomes a NEGATIVE [LazyListState.scrollBy] argument. This sign
     * flip is exactly what Compose's built-in ScrollCapture declines to do for reversed
     * containers, which is why the platform never offers "capture more" in the thread view.
     */
    fun scrollDeltaForCaptureTop(captureTop: Int, reverseLayout: Boolean): Int =
        if (reverseLayout) -captureTop else captureTop

    /**
     * Rows we can actually fill for a requested band of height [captureHeight], given the
     * live [viewportHeight]. A tile taller than the viewport is clamped to a single
     * viewport's worth (the platform slides the band and re-requests the remainder);
     * a non-positive request yields zero.
     */
    fun capturableRows(captureHeight: Int, viewportHeight: Int): Int =
        captureHeight.coerceIn(0, viewportHeight.coerceAtLeast(0))
}
