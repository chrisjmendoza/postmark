package com.plusorminustwo.postmark.domain.backup

import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Thread
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSelectionTest {

    private fun thread(
        id: Long,
        policy: BackupPolicy = BackupPolicy.GLOBAL,
        displayName: String = "Thread $id",
        address: String = "+1206555$id",
        nickname: String? = null
    ) = Thread(
        id = id,
        displayName = displayName,
        address = address,
        lastMessageAt = 0L,
        backupPolicy = policy,
        nickname = nickname
    )

    private val threads = listOf(
        thread(1),
        thread(2, policy = BackupPolicy.NEVER_INCLUDE),
        thread(3, policy = BackupPolicy.ALWAYS_INCLUDE)
    )

    // ---- selectThreadsForExport ----

    @Test
    fun `select-all honors NEVER_INCLUDE`() {
        val selected = selectThreadsForExport(threads, BackupSelection())
        assertEquals(listOf(1L, 3L), selected.map { it.id })
    }

    @Test
    fun `explicit picks are exact even for NEVER_INCLUDE threads`() {
        // A deliberate export beats the standing policy.
        val selected = selectThreadsForExport(
            threads, BackupSelection(threadIds = setOf(2L, 3L))
        )
        assertEquals(listOf(2L, 3L), selected.map { it.id })
    }

    @Test
    fun `explicit picks exclude everything not picked`() {
        val selected = selectThreadsForExport(threads, BackupSelection(threadIds = setOf(1L)))
        assertEquals(listOf(1L), selected.map { it.id })
    }

    @Test
    fun `empty explicit pick set selects nothing`() {
        assertTrue(selectThreadsForExport(threads, BackupSelection(threadIds = emptySet())).isEmpty())
    }

    // ---- hasDateRange ----

    @Test
    fun `defaults mean no date range`() {
        assertFalse(BackupSelection().hasDateRange)
        assertTrue(BackupSelection(startMs = 1L).hasDateRange)
        assertTrue(BackupSelection(endMs = 5L).hasDateRange)
    }

    // ---- localDateRangeToMillisBounds ----

    private val utc = ZoneId.of("UTC")
    private val la = ZoneId.of("America/Los_Angeles")

    @Test
    fun `bounds cover the full days inclusively`() {
        val (start, end) = localDateRangeToMillisBounds(
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2), utc
        )
        // 2026-07-01T00:00Z
        assertEquals(java.time.Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(), start)
        // last millisecond of 2026-07-02 UTC
        assertEquals(java.time.Instant.parse("2026-07-03T00:00:00Z").toEpochMilli() - 1, end)
    }

    @Test
    fun `single-day range spans exactly that day`() {
        val day = LocalDate.of(2026, 7, 11)
        val (start, end) = localDateRangeToMillisBounds(day, day, utc)
        assertEquals(86_400_000L - 1, end - start)
    }

    @Test
    fun `bounds respect the zone`() {
        val day = LocalDate.of(2026, 7, 11)
        val (utcStart, _) = localDateRangeToMillisBounds(day, day, utc)
        val (laStart, _) = localDateRangeToMillisBounds(day, day, la)
        // LA midnight is 7 hours after UTC midnight in July (PDT).
        assertEquals(7 * 3_600_000L, laStart - utcStart)
    }

    // ---- filterThreadsForExport ----

    private val named = listOf(
        thread(1, displayName = "Alice Smith", address = "+12065551111"),
        thread(2, displayName = "Bob Jones", address = "+14255552222", nickname = "Bobby"),
        thread(3, displayName = "87892", address = "87892")
    )

    @Test
    fun `blank query returns everything`() {
        assertEquals(named, filterThreadsForExport(named, ""))
        assertEquals(named, filterThreadsForExport(named, "   "))
    }

    @Test
    fun `matches name nickname and address case-insensitively`() {
        assertEquals(listOf(1L), filterThreadsForExport(named, "alice").map { it.id })
        assertEquals(listOf(2L), filterThreadsForExport(named, "BOBBY").map { it.id })
        assertEquals(listOf(1L), filterThreadsForExport(named, "206555").map { it.id })
        assertEquals(listOf(3L), filterThreadsForExport(named, "87892").map { it.id })
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(filterThreadsForExport(named, "zzz").isEmpty())
    }
}
