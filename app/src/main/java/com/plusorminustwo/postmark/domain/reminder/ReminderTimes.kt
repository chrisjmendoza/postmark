package com.plusorminustwo.postmark.domain.reminder

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A preset "remind me at" choice offered by the reminder time-picker sheet: a human label
 * and the resolved absolute instant it maps to.
 *
 * @param label       ready-to-render row label ("In 1 hour", "This evening (6 PM)", …).
 * @param epochMillis the absolute instant the reminder should fire, epoch millis.
 */
data class ReminderPreset(val label: String, val epochMillis: Long)

/** Evening preset hour (6 PM) and the cutoff past which "this evening" rolls to tomorrow. */
private val EVENING = LocalTime.of(18, 0)
private val EVENING_CUTOFF = LocalTime.of(17, 0)  // past 5 PM → offer tomorrow evening instead
private val MORNING = LocalTime.of(9, 0)

/**
 * The preset reminder times shown, in order, above the custom "Pick date & time" option.
 *
 * Pure and deterministic: [nowMs] and [zone] are injected (FriendlyTime house style), so every
 * band boundary is unit-testable and the result never reads the wall clock. All arithmetic goes
 * through [java.time.ZonedDateTime], so it is DST-safe — "tomorrow at 9 AM" is 9 AM local even
 * across a spring-forward / fall-back day, never a fixed +24h offset.
 *
 * Presets:
 *  - "In 1 hour"                — always [nowMs] + 1h; always in the future.
 *  - "This evening (6 PM)"      — 6 PM today, UNLESS it's already past 5 PM local, in which case
 *                                 the row becomes "Tomorrow evening (6 PM)" at 6 PM tomorrow (so
 *                                 the offered evening is never in the past or uselessly imminent).
 *  - "Tomorrow morning (9 AM)"  — 9 AM the next calendar day; always in the future.
 *
 * Every returned preset's [ReminderPreset.epochMillis] is strictly greater than [nowMs].
 */
fun reminderPresets(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<ReminderPreset> {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)

    val inOneHour = now.plusHours(1)

    val pastCutoff = !now.toLocalTime().isBefore(EVENING_CUTOFF)  // now >= 17:00
    val eveningDate = if (pastCutoff) now.toLocalDate().plusDays(1) else now.toLocalDate()
    val evening = eveningDate.atTime(EVENING).atZone(zone)
    val eveningLabel = if (pastCutoff) "Tomorrow evening (6 PM)" else "This evening (6 PM)"

    val tomorrowMorning = now.toLocalDate().plusDays(1).atTime(MORNING).atZone(zone)

    return listOf(
        ReminderPreset("In 1 hour", inOneHour.toInstant().toEpochMilli()),
        ReminderPreset(eveningLabel, evening.toInstant().toEpochMilli()),
        ReminderPreset("Tomorrow morning (9 AM)", tomorrowMorning.toInstant().toEpochMilli())
    )
}
