package com.plusorminustwo.postmark.service.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [allocateAttachmentBudgets] — the pure function that divides the
 * carrier's PDU size budget across all attachments of one MMS.
 *
 * The invariant that matters on real carriers: the SUM of the returned budgets
 * never exceeds the total budget, because the MMSC enforces the cap on the whole
 * PDU, not per attachment.
 */
class AttachmentBudgetTest {

    private val img = true   // compressible
    private val vid = false  // fixed-size (video/audio)

    @Test fun `everything fits — sizes returned unchanged`() {
        val sizes = listOf(100_000, 200_000, 50_000)
        assertEquals(sizes, allocateAttachmentBudgets(sizes, listOf(img, img, vid), 400_000))
    }

    @Test fun `single oversized image gets the whole budget`() {
        assertEquals(listOf(500_000), allocateAttachmentBudgets(listOf(2_000_000), listOf(img), 500_000))
    }

    @Test fun `oversized images split the budget equally`() {
        val budgets = allocateAttachmentBudgets(
            listOf(900_000, 900_000), listOf(img, img), 600_000
        )!!
        assertEquals(listOf(300_000, 300_000), budgets)
    }

    @Test fun `small image donates its surplus to the large one`() {
        // Fair share is 300k each, but the first image only needs 100k —
        // the second should receive the leftover 500k.
        val budgets = allocateAttachmentBudgets(
            listOf(100_000, 900_000), listOf(img, img), 600_000
        )!!
        assertEquals(listOf(100_000, 500_000), budgets)
    }

    @Test fun `non-compressible part keeps its size and images share the rest`() {
        val budgets = allocateAttachmentBudgets(
            listOf(400_000, 900_000, 900_000), listOf(vid, img, img), 800_000
        )!!
        assertEquals(400_000, budgets[0])          // video untouched
        assertEquals(200_000, budgets[1])          // (800k - 400k) / 2
        assertEquals(200_000, budgets[2])
    }

    @Test fun `non-compressible media alone over budget returns null`() {
        assertNull(allocateAttachmentBudgets(listOf(900_000), listOf(vid), 800_000))
        assertNull(
            allocateAttachmentBudgets(
                listOf(500_000, 400_000, 10_000), listOf(vid, vid, img), 800_000
            )
        )
    }

    @Test fun `sum of budgets never exceeds the total budget`() {
        val cases = listOf(
            Triple(listOf(900_000, 900_000, 900_000), listOf(img, img, img), 855_160),
            Triple(listOf(123_456, 654_321, 42),      listOf(img, img, img), 300_000),
            Triple(listOf(400_000, 900_000),          listOf(vid, img),      500_000),
            Triple(listOf(1, 2, 3),                   listOf(img, img, img), 2)
        )
        for ((sizes, kinds, budget) in cases) {
            val budgets = allocateAttachmentBudgets(sizes, kinds, budget)
            if (budgets != null) {
                assertTrue(
                    "sum=${budgets.sum()} budget=$budget for sizes=$sizes",
                    budgets.sum() <= budget
                )
            }
        }
    }

    @Test fun `budgets are returned in the original attachment order`() {
        // Largest image first — the greedy pass sorts by size internally, but the
        // returned list must still line up index-for-index with the input.
        val budgets = allocateAttachmentBudgets(
            listOf(900_000, 100_000), listOf(img, img), 600_000
        )!!
        assertEquals(listOf(500_000, 100_000), budgets)
    }

    @Test fun `no attachment ever gets a negative budget`() {
        val budgets = allocateAttachmentBudgets(
            listOf(900_000, 900_000, 900_000, 900_000, 900_000),
            List(5) { img },
            300_000
        )!!
        assertTrue(budgets.all { it >= 0 })
        assertTrue(budgets.sum() <= 300_000)
    }
}
