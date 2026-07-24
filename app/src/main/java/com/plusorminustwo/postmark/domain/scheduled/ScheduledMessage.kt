package com.plusorminustwo.postmark.domain.scheduled

/**
 * A text-only SMS the user parked (via long-press "Schedule send") to transmit at a chosen
 * future time. Distinct from a normal [com.plusorminustwo.postmark.domain.model.Message]: a
 * scheduled send must NEVER enter the telephony provider or the `messages` table early — sync
 * dedup/healing must never see it, and no thread/search/stats query may leak it — so it lives
 * in its own `scheduled_messages` table until it actually fires (see [ScheduledMessageEntity]).
 *
 * @param id          Room-assigned primary key (autoGenerate); also names the WorkManager job
 *                    `scheduled_send_<id>`.
 * @param threadId    Owning conversation (same telephony thread id used everywhere else).
 * @param address     Destination address the text is sent to when it fires.
 * @param body        The text to send. Always non-blank (blank bodies are rejected at schedule
 *                    time — see [validateScheduledSend]).
 * @param scheduledAt Absolute epoch-millis the message should send.
 * @param createdAt   Absolute epoch-millis the schedule was created (for future sorting/debug).
 */
data class ScheduledMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val scheduledAt: Long,
    val createdAt: Long,
)

/** Result of validating a "Schedule send" request before it is persisted. */
enum class ScheduleValidation { VALID, BLANK_BODY, PAST_TIME }

/**
 * Pure guard for a schedule-send request. A scheduled message must carry a non-blank body and
 * a strictly-future send time. Injected [nowMs] keeps this deterministic and unit-testable
 * (house FriendlyTime style) — the wall clock is never read here.
 */
fun validateScheduledSend(body: String, scheduledAtMs: Long, nowMs: Long): ScheduleValidation = when {
    body.isBlank() -> ScheduleValidation.BLANK_BODY
    scheduledAtMs <= nowMs -> ScheduleValidation.PAST_TIME
    else -> ScheduleValidation.VALID
}

/** What [com.plusorminustwo.postmark.service.scheduled.ScheduledSendWorker] does when its job fires. */
enum class ScheduledSendOutcome {
    /** The row is gone (user cancelled / sent-now between scheduling and firing) — nothing to do. */
    ROW_MISSING,

    /** Postmark is no longer the default SMS app — keep the row, notify the user, don't retry. */
    NOT_DEFAULT_SMS,

    /** Happy path — delete the row and hand the text to the normal SMS send path. */
    SEND,
}

/**
 * Pure decision table for the scheduled-send worker, extracted so the row-missing /
 * not-default-SMS / happy-path branches are testable without WorkManager or Room.
 */
fun scheduledSendDecision(rowExists: Boolean, isDefaultSms: Boolean): ScheduledSendOutcome = when {
    !rowExists -> ScheduledSendOutcome.ROW_MISSING
    !isDefaultSms -> ScheduledSendOutcome.NOT_DEFAULT_SMS
    else -> ScheduledSendOutcome.SEND
}

/**
 * Scheduled rows in the order the thread renders them: soonest send first. Pure so the
 * ordering rule can be tested without Room's ORDER BY (the DAO applies the same sort).
 */
fun sortScheduledForDisplay(items: List<ScheduledMessage>): List<ScheduledMessage> =
    items.sortedBy { it.scheduledAt }
