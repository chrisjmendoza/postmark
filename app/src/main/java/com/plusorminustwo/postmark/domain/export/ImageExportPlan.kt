package com.plusorminustwo.postmark.domain.export

/*
 * Pure sizing / pagination math for rendering selected messages to a shareable image.
 *
 * A selection can span far more than one screen of messages, so the chat is drawn to a
 * fixed-width bitmap and, when the total height would exceed a safe bitmap ceiling, split
 * across several sequential PNGs ("part 1 of N"). All the decision-making — how tall the
 * whole thing is, and where to cut it into pages — lives here as plain JVM functions so it
 * is unit-testable without a device. The Android renderer (ImageExportRenderer) measures
 * each row with StaticLayout and feeds the resulting heights in; it never decides layout.
 */
object ImageExportPlan {

    /** Fixed render width in pixels — every page is exactly this wide. */
    const val WIDTH_PX = 1080

    /** Hard bitmap-height ceiling per page. Bitmaps beyond a few thousand pixels start to
     *  risk allocation failures on low-memory devices; 12000 keeps a comfortable margin
     *  while still fitting a long conversation on one page. */
    const val MAX_PAGE_HEIGHT_PX = 12_000

    /**
     * One rendered page: the half-open row range it covers plus its pixel heights.
     *
     * @param startRow          Index of the first content row on this page (inclusive).
     * @param endRowExclusive   Index one past the last content row on this page.
     * @param contentHeightPx   Summed height of this page's content rows (no header/footer).
     * @param totalHeightPx     Full bitmap height: header + content + footer.
     */
    data class Page(
        val startRow: Int,
        val endRowExclusive: Int,
        val contentHeightPx: Int,
        val totalHeightPx: Int
    ) {
        val rowCount: Int get() = endRowExclusive - startRow
    }

    /**
     * Height of a single page holding every row (header + all rows + footer). Useful as a
     * cheap "does the whole selection fit on one page?" check before deciding to paginate.
     */
    fun singlePageHeight(rowHeights: List<Int>, headerHeightPx: Int, footerHeightPx: Int): Int =
        headerHeightPx + rowHeights.sum() + footerHeightPx

    /**
     * Greedily packs [rowHeights] (each an already-measured content row, in draw order) into
     * as few pages as possible without any page exceeding [maxPageHeightPx]. Every page
     * reserves [headerHeightPx] at the top and [footerHeightPx] at the bottom, so the space
     * available for content on each page is `maxPageHeightPx - headerHeightPx - footerHeightPx`.
     *
     * A row is never split across pages: a row that on its own is taller than the available
     * content budget still occupies a page by itself (its page's totalHeightPx then exceeds
     * [maxPageHeightPx] — accepted as the least-bad option, since a single message cannot be
     * cut in half). This is rare in practice (it takes a genuinely enormous single message).
     *
     * @return one [Page] per output image, in order. Empty when [rowHeights] is empty.
     */
    fun paginate(
        rowHeights: List<Int>,
        headerHeightPx: Int,
        footerHeightPx: Int,
        maxPageHeightPx: Int = MAX_PAGE_HEIGHT_PX
    ): List<Page> {
        val budget = maxPageHeightPx - headerHeightPx - footerHeightPx
        require(budget > 0) {
            "header ($headerHeightPx) + footer ($footerHeightPx) leave no room within maxPageHeightPx ($maxPageHeightPx)"
        }
        if (rowHeights.isEmpty()) return emptyList()

        val pages = mutableListOf<Page>()
        var start = 0
        var acc = 0
        for (i in rowHeights.indices) {
            val h = rowHeights[i]
            // Start a new page when adding this row would overflow — but only if the current
            // page already holds at least one row (acc > 0), so an oversized lone row is never
            // orphaned into an empty page ahead of itself.
            if (acc > 0 && acc + h > budget) {
                pages += Page(start, i, acc, acc + headerHeightPx + footerHeightPx)
                start = i
                acc = 0
            }
            acc += h
        }
        pages += Page(start, rowHeights.size, acc, acc + headerHeightPx + footerHeightPx)
        return pages
    }
}
