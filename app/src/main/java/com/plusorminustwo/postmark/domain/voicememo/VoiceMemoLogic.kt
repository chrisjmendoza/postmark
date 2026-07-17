package com.plusorminustwo.postmark.domain.voicememo

/**
 * Pure state machine + gesture math for the reply-bar voice memo recorder.
 *
 * Everything here is JVM-testable; the impure halves (MediaRecorder, files,
 * haptics) live in VoiceMemoRecorder and ThreadViewModel, which apply the
 * [VoiceMemoEffect] each transition returns.
 *
 * There is deliberately no PREVIEW phase: a finished memo is appended to the
 * existing pending-attachments queue and reviewed in the same preview strip
 * every other attachment uses — a fourth state would duplicate that source
 * of truth. Any stop therefore returns to [VoiceMemoPhase.IDLE].
 */
enum class VoiceMemoPhase {
    /** Not recording. */
    IDLE,
    /** Recording while the mic button is physically held. */
    HELD,
    /** Hands-free recording after the slide-up latch. */
    LOCKED
}

enum class VoiceMemoEvent {
    /** Finger down on the mic button (permission already granted). */
    PRESS,
    /** Slide-up crossed the lock threshold while held. */
    LATCH_LOCK,
    /** Finger lifted. */
    RELEASE,
    /** Stop button tapped in locked mode. */
    STOP_TAP,
    /** Slide-away past the cancel threshold, or the locked-mode cancel button. */
    CANCEL,
    /** MediaRecorder hit the MMS-budget duration cap. */
    CAP_REACHED
}

/** What the caller must do alongside a phase change. */
enum class VoiceMemoEffect {
    NONE,
    /** Start capturing audio. */
    START,
    /** Stop capturing; keep the file and queue it as a pending attachment. */
    STOP_KEEP,
    /** Stop capturing; delete the file. */
    STOP_DISCARD
}

data class VoiceMemoTransition(val phase: VoiceMemoPhase, val effect: VoiceMemoEffect)

/**
 * The complete transition table. Unlisted (phase, event) pairs are explicit
 * no-ops — e.g. RELEASE while LOCKED (lifting the finger after latching is
 * the whole point of the lock) or a stray CAP_REACHED after a stop already
 * ran (the recorder's info callback can race a user stop).
 */
fun voiceMemoTransition(phase: VoiceMemoPhase, event: VoiceMemoEvent): VoiceMemoTransition =
    when (phase) {
        VoiceMemoPhase.IDLE -> when (event) {
            VoiceMemoEvent.PRESS -> VoiceMemoTransition(VoiceMemoPhase.HELD, VoiceMemoEffect.START)
            else                 -> VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.NONE)
        }
        VoiceMemoPhase.HELD -> when (event) {
            VoiceMemoEvent.LATCH_LOCK  -> VoiceMemoTransition(VoiceMemoPhase.LOCKED, VoiceMemoEffect.NONE)
            VoiceMemoEvent.RELEASE,
            VoiceMemoEvent.STOP_TAP,
            VoiceMemoEvent.CAP_REACHED -> VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_KEEP)
            VoiceMemoEvent.CANCEL      -> VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_DISCARD)
            VoiceMemoEvent.PRESS       -> VoiceMemoTransition(VoiceMemoPhase.HELD, VoiceMemoEffect.NONE)
        }
        VoiceMemoPhase.LOCKED -> when (event) {
            VoiceMemoEvent.STOP_TAP,
            VoiceMemoEvent.CAP_REACHED -> VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_KEEP)
            VoiceMemoEvent.CANCEL      -> VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_DISCARD)
            else                       -> VoiceMemoTransition(VoiceMemoPhase.LOCKED, VoiceMemoEffect.NONE)
        }
    }

/** True when an upward drag of [dragDeltaY] px (negative = up) should latch the lock. */
fun shouldLatchLock(dragDeltaY: Float, thresholdPx: Float): Boolean =
    thresholdPx > 0f && dragDeltaY <= -thresholdPx

/** True when a leftward drag of [dragDeltaX] px (negative = left) should cancel. */
fun shouldCancelDrag(dragDeltaX: Float, thresholdPx: Float): Boolean =
    thresholdPx > 0f && dragDeltaX <= -thresholdPx

/**
 * Accidental-tap guard: a press shorter than this produces no audible content
 * (and MediaRecorder.stop() typically throws before it), so it is discarded
 * instead of queued.
 */
const val MIN_VOICE_MEMO_DURATION_MS = 500L

fun isMemoKeepable(elapsedMs: Long, minDurationMs: Long = MIN_VOICE_MEMO_DURATION_MS): Boolean =
    elapsedMs >= minDurationMs

/** Formats a duration as "m:ss" for the recording timer and audio chips. */
fun formatMemoDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
