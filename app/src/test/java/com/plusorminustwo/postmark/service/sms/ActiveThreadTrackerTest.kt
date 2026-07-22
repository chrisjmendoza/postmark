package com.plusorminustwo.postmark.service.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ActiveThreadTracker] — the record of which thread is on screen, used to
 * suppress the incoming-message notification for the conversation the user is looking at.
 *
 * The class is plain Kotlin (no Android deps), so it is exercised directly here. The one
 * behaviour worth pinning down is clear-on-match: [clearActive] must only null the field
 * when its id equals the stored one, so a departing ThreadScreen's ON_PAUSE can't wipe a
 * newer screen's registration during a pause-old/resume-new navigation.
 */
class ActiveThreadTrackerTest {

    @Test
    fun `starts null`() {
        assertNull(ActiveThreadTracker().activeThreadId)
    }

    @Test
    fun `setActive records the id`() {
        val tracker = ActiveThreadTracker()
        tracker.setActive(42L)
        assertEquals(42L, tracker.activeThreadId)
    }

    @Test
    fun `clearActive with matching id nulls the field`() {
        val tracker = ActiveThreadTracker()
        tracker.setActive(42L)
        tracker.clearActive(42L)
        assertNull(tracker.activeThreadId)
    }

    @Test
    fun `clearActive with stale id leaves the field unchanged`() {
        val tracker = ActiveThreadTracker()
        tracker.setActive(42L)
        // A stale ON_PAUSE from a different (older) thread must not clear the current one.
        tracker.clearActive(7L)
        assertEquals(42L, tracker.activeThreadId)
    }

    @Test
    fun `clearActive when nothing active is a no-op`() {
        val tracker = ActiveThreadTracker()
        tracker.clearActive(42L)
        assertNull(tracker.activeThreadId)
    }

    @Test
    fun `overlapping navigation keeps the newer thread registered`() {
        val tracker = ActiveThreadTracker()
        // Thread A on screen.
        tracker.setActive(1L)
        // Navigate to thread B: B resumes before A pauses (common Compose ordering).
        tracker.setActive(2L)
        tracker.clearActive(1L) // A's late ON_PAUSE — must not clear B.
        assertEquals(2L, tracker.activeThreadId)
    }

    @Test
    fun `pausing the current thread after a clean handoff clears it`() {
        val tracker = ActiveThreadTracker()
        tracker.setActive(2L)
        tracker.clearActive(2L)
        assertNull(tracker.activeThreadId)
    }

    @Test
    fun `setActive twice with the same id is idempotent`() {
        val tracker = ActiveThreadTracker()
        tracker.setActive(5L)
        tracker.setActive(5L)
        assertEquals(5L, tracker.activeThreadId)
        tracker.clearActive(5L)
        assertNull(tracker.activeThreadId)
    }
}
