package com.plusorminustwo.postmark.service.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Tests for the two pure backup-scheduling functions:
 * [selectBackupsToPrune] (retention is a data-loss invariant — the just-written
 * backup counts toward the total and must never be deleted) and
 * [calculateInitialDelay] (day/week/month rollover).
 */
class BackupSchedulerLogicTest {

    // ── selectBackupsToPrune ───────────────────────────────────────────────────
    // Called AFTER the new backup is written, so the input always includes it.

    @Test
    fun `retention 1 keeps only the newest file — the just-written backup`() {
        val files = listOf(
            "postmark_old1.json" to 1_000L,
            "postmark_old2.json" to 2_000L,
            "postmark_new.json"  to 3_000L
        )
        val doomed = selectBackupsToPrune(files, retention = 1)
        assertEquals(setOf("postmark_old1.json", "postmark_old2.json"), doomed)
    }

    @Test
    fun `retention at or above file count deletes nothing`() {
        val files = listOf(
            "postmark_a.json" to 1_000L,
            "postmark_b.json" to 2_000L
        )
        assertTrue(selectBackupsToPrune(files, retention = 2).isEmpty())
        assertTrue(selectBackupsToPrune(files, retention = 5).isEmpty())
    }

    @Test
    fun `pruning selects by timestamp, not list order or name`() {
        // Newest file listed first with an alphabetically-early name — only
        // lastModified may decide survival.
        val files = listOf(
            "postmark_aaa.json" to 9_000L,
            "postmark_zzz.json" to 1_000L,
            "postmark_mmm.json" to 5_000L
        )
        val doomed = selectBackupsToPrune(files, retention = 2)
        assertEquals(setOf("postmark_zzz.json"), doomed)
    }

    // ── calculateInitialDelay ──────────────────────────────────────────────────

    /** Wednesday 2026-07-08 10:00:00.000 local time. */
    private fun wednesdayAt10(): Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 8, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private val HOUR = 60 * 60 * 1_000L
    private val DAY = 24 * HOUR

    @Test
    fun `daily target later today waits until today's occurrence`() {
        val delay = calculateInitialDelay(
            BackupFrequency.DAILY, hourOfDay = 14, minuteOfHour = 0,
            dayOfWeek = Calendar.SUNDAY, dayOfMonth = 1, now = wednesdayAt10()
        )
        assertEquals(4 * HOUR, delay)
    }

    @Test
    fun `daily target already passed today rolls over to tomorrow`() {
        val delay = calculateInitialDelay(
            BackupFrequency.DAILY, hourOfDay = 2, minuteOfHour = 0,
            dayOfWeek = Calendar.SUNDAY, dayOfMonth = 1, now = wednesdayAt10()
        )
        assertEquals(DAY - 8 * HOUR, delay)  // 02:00 tomorrow, 16h away
    }

    @Test
    fun `daily target exactly now fires immediately`() {
        val delay = calculateInitialDelay(
            BackupFrequency.DAILY, hourOfDay = 10, minuteOfHour = 0,
            dayOfWeek = Calendar.SUNDAY, dayOfMonth = 1, now = wednesdayAt10()
        )
        assertEquals(0L, delay)
    }

    @Test
    fun `weekly target earlier in the week rolls over to next week`() {
        // Now is Wednesday; Sunday 02:00 of this week is already past.
        val delay = calculateInitialDelay(
            BackupFrequency.WEEKLY, hourOfDay = 2, minuteOfHour = 0,
            dayOfWeek = Calendar.SUNDAY, dayOfMonth = 1, now = wednesdayAt10()
        )
        // Next Sunday 2026-07-12 02:00 is 3 days 16 hours away.
        assertEquals(3 * DAY + 16 * HOUR, delay)
    }

    @Test
    fun `weekly target later in the week waits within this week`() {
        // Now is Wednesday; Friday 02:00 is still ahead.
        val delay = calculateInitialDelay(
            BackupFrequency.WEEKLY, hourOfDay = 2, minuteOfHour = 0,
            dayOfWeek = Calendar.FRIDAY, dayOfMonth = 1, now = wednesdayAt10()
        )
        assertEquals(DAY + 16 * HOUR, delay)  // Friday 2026-07-10 02:00
    }

    @Test
    fun `monthly target on a passed day rolls over to next month`() {
        // Now is July 8; the 1st at 02:00 has passed → August 1 02:00.
        val delay = calculateInitialDelay(
            BackupFrequency.MONTHLY, hourOfDay = 2, minuteOfHour = 0,
            dayOfWeek = Calendar.SUNDAY, dayOfMonth = 1, now = wednesdayAt10()
        )
        // July has 31 days: July 8 10:00 → Aug 1 02:00 = 23 days 16 hours.
        assertEquals(23 * DAY + 16 * HOUR, delay)
    }

    @Test
    fun `monthly target on an upcoming day waits within this month`() {
        val delay = calculateInitialDelay(
            BackupFrequency.MONTHLY, hourOfDay = 2, minuteOfHour = 0,
            dayOfWeek = Calendar.SUNDAY, dayOfMonth = 15, now = wednesdayAt10()
        )
        assertEquals(6 * DAY + 16 * HOUR, delay)  // July 15 02:00
    }
}
