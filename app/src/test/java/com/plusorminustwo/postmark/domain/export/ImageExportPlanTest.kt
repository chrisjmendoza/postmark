package com.plusorminustwo.postmark.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageExportPlanTest {

    private val header = 100
    private val footer = 60

    @Test
    fun `empty rows produce no pages`() {
        val pages = ImageExportPlan.paginate(emptyList(), header, footer, maxPageHeightPx = 1000)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun `single page height is header plus rows plus footer`() {
        val rows = listOf(200, 300, 150)
        assertEquals(100 + 650 + 60, ImageExportPlan.singlePageHeight(rows, header, footer))
    }

    @Test
    fun `all rows fit on one page`() {
        // budget = 1000 - 100 - 60 = 840; rows sum to 650 <= 840
        val rows = listOf(200, 300, 150)
        val pages = ImageExportPlan.paginate(rows, header, footer, maxPageHeightPx = 1000)
        assertEquals(1, pages.size)
        val p = pages.single()
        assertEquals(0, p.startRow)
        assertEquals(3, p.endRowExclusive)
        assertEquals(650, p.contentHeightPx)
        assertEquals(100 + 650 + 60, p.totalHeightPx)
        assertEquals(3, p.rowCount)
    }

    @Test
    fun `overflow splits into two pages at the right boundary`() {
        // budget = 840. Rows: 400, 400, 400 -> page1 [0,2)=800, page2 [2,3)=400
        val rows = listOf(400, 400, 400)
        val pages = ImageExportPlan.paginate(rows, header, footer, maxPageHeightPx = 1000)
        assertEquals(2, pages.size)
        assertEquals(0, pages[0].startRow); assertEquals(2, pages[0].endRowExclusive)
        assertEquals(800, pages[0].contentHeightPx)
        assertEquals(2, pages[1].startRow); assertEquals(3, pages[1].endRowExclusive)
        assertEquals(400, pages[1].contentHeightPx)
    }

    @Test
    fun `exact-fit boundary keeps rows on the same page`() {
        // budget = 840. Rows 420 + 420 == 840 exactly -> one page (not an overflow).
        val rows = listOf(420, 420)
        val pages = ImageExportPlan.paginate(rows, header, footer, maxPageHeightPx = 1000)
        assertEquals(1, pages.size)
        assertEquals(840, pages.single().contentHeightPx)
    }

    @Test
    fun `one-over-boundary starts a new page`() {
        // budget = 840. 420 + 421 = 841 > 840 -> two pages.
        val rows = listOf(420, 421)
        val pages = ImageExportPlan.paginate(rows, header, footer, maxPageHeightPx = 1000)
        assertEquals(2, pages.size)
    }

    @Test
    fun `oversized lone row occupies its own page and does not swallow the next row`() {
        // budget = 840. Row 0 is 900 (> budget) -> its own page; rows 1,2 pack onto page 2.
        val rows = listOf(900, 300, 300)
        val pages = ImageExportPlan.paginate(rows, header, footer, maxPageHeightPx = 1000)
        assertEquals(2, pages.size)
        assertEquals(0, pages[0].startRow); assertEquals(1, pages[0].endRowExclusive)
        assertEquals(900, pages[0].contentHeightPx)
        // The oversized page legitimately exceeds the ceiling — accepted, documented.
        assertTrue(pages[0].totalHeightPx > 1000)
        assertEquals(1, pages[1].startRow); assertEquals(3, pages[1].endRowExclusive)
        assertEquals(600, pages[1].contentHeightPx)
    }

    @Test
    fun `every content row lands on exactly one page in order`() {
        val rows = List(20) { 100 + it * 25 }
        val pages = ImageExportPlan.paginate(rows, header, footer, maxPageHeightPx = 1000)
        // Rows are contiguous and cover the whole list with no gaps or overlaps.
        var expectedStart = 0
        for (p in pages) {
            assertEquals(expectedStart, p.startRow)
            expectedStart = p.endRowExclusive
        }
        assertEquals(rows.size, expectedStart)
        // No page (other than a lone-oversized one, absent here) exceeds the ceiling.
        assertTrue(pages.all { it.totalHeightPx <= 1000 })
    }

    @Test
    fun `no room for content throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageExportPlan.paginate(listOf(10), headerHeightPx = 600, footerHeightPx = 500, maxPageHeightPx = 1000)
        }
    }
}
