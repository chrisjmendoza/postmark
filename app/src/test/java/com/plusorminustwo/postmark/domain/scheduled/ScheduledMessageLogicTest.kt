package com.plusorminustwo.postmark.domain.scheduled

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the "Schedule send" domain helpers — validation, the worker decision
 * table, and display ordering. Plain JUnit, no Android/Room/WorkManager; mirrors the reminder
 * test style.
 */
class ScheduledMessageLogicTest {

    private val now = 1_000_000_000_000L

    // ── validateScheduledSend ─────────────────────────────────────────────

    @Test
    fun `future time with non-blank body is valid`() {
        assertEquals(
            ScheduleValidation.VALID,
            validateScheduledSend("see you at 6", now + 60_000, now)
        )
    }

    @Test
    fun `blank body is rejected`() {
        assertEquals(ScheduleValidation.BLANK_BODY, validateScheduledSend("   ", now + 60_000, now))
    }

    @Test
    fun `blank body is checked before past time`() {
        // A blank body in the past reports BLANK_BODY (the body guard comes first).
        assertEquals(ScheduleValidation.BLANK_BODY, validateScheduledSend("", now - 60_000, now))
    }

    @Test
    fun `past time is rejected`() {
        assertEquals(ScheduleValidation.PAST_TIME, validateScheduledSend("hi", now - 1, now))
    }

    @Test
    fun `exactly now is rejected as past`() {
        // Strictly-future only — a time equal to now is not in the future.
        assertEquals(ScheduleValidation.PAST_TIME, validateScheduledSend("hi", now, now))
    }

    // ── scheduledSendDecision ─────────────────────────────────────────────

    @Test
    fun `missing row short-circuits regardless of default-sms state`() {
        assertEquals(
            ScheduledSendOutcome.ROW_MISSING,
            scheduledSendDecision(rowExists = false, isDefaultSms = true)
        )
        assertEquals(
            ScheduledSendOutcome.ROW_MISSING,
            scheduledSendDecision(rowExists = false, isDefaultSms = false)
        )
    }

    @Test
    fun `present row but not default sms notifies`() {
        assertEquals(
            ScheduledSendOutcome.NOT_DEFAULT_SMS,
            scheduledSendDecision(rowExists = true, isDefaultSms = false)
        )
    }

    @Test
    fun `present row and default sms sends`() {
        assertEquals(
            ScheduledSendOutcome.SEND,
            scheduledSendDecision(rowExists = true, isDefaultSms = true)
        )
    }

    // ── sortScheduledForDisplay ───────────────────────────────────────────

    @Test
    fun `sorts soonest send first`() {
        fun row(id: Long, at: Long) = ScheduledMessage(id, 1L, "+1", "b", at, 0L)
        val sorted = sortScheduledForDisplay(
            listOf(row(1, now + 3000), row(2, now + 1000), row(3, now + 2000))
        )
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }
}
