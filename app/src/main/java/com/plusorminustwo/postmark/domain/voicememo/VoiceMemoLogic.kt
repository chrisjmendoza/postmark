package com.plusorminustwo.postmark.domain.voicememo

/**
 * Pure state machine + gesture math for the reply-bar voice memo recorder.
 *
 * Everything here is JVM-testable; the impure halves (MediaRecorder, files,
 * haptics) live in VoiceMemoRecorder and ThreadViewModel, which apply the
 * [VoiceMemoEffect] each transition returns.
 *
 * Two flows share the machine. The quick flow (hold, release) skips PREVIEW —
 * the memo drops straight into the pending-attachments queue, whose strip is
 * its review surface. The deliberate flow (slide-up lock) ends in [PREVIEW]:
 * the take is stopped but NOT yet attached, and the recording panel offers
 * play/scrub plus Restart / Discard / Attach (the Google Messages pattern).
 */
enum class VoiceMemoPhase {
    /** Not recording. */
    IDLE,
    /** Recording while the mic button is physically held. */
    HELD,
    /** Hands-free recording after the slide-up latch. */
    LOCKED,
    /** A locked-mode take is stopped and awaiting review — attach, restart, or discard. */
    PREVIEW
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
    /** Slide-away past the cancel threshold, or the panel's cancel/discard button. */
    CANCEL,
    /** MediaRecorder hit the MMS-budget duration cap. */
    CAP_REACHED,
    /** Panel restart button — throw away the current take and record a new one. */
    RESTART,
    /** Panel attach button — queue the previewed take as a pending attachment. */
    ATTACH
}

/** What the caller must do alongside a phase change. */
enum class VoiceMemoEffect {
    NONE,
    /** Start capturing audio. */
    START,
    /** Stop capturing; keep the file and queue it as a pending attachment. */
    STOP_KEEP,
    /** Stop capturing; keep the file for the preview panel — do NOT attach yet. */
    STOP_PREVIEW,
    /** Stop capturing; delete the file. */
    STOP_DISCARD,
    /** Queue the previewed take as a pending attachment. */
    ATTACH_PREVIEW,
    /** Delete the previewed take. */
    DISCARD_PREVIEW,
    /** Discard the current take (in-flight recording or held preview) and record anew. */
    RESTART_RECORDING
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
            // Quick flow: straight to the pending strip, no preview stop-over.
            VoiceMemoEvent.RELEASE,
            VoiceMemoEvent.STOP_TAP,
            VoiceMemoEvent.CAP_REACHED -> VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_KEEP)
            VoiceMemoEvent.CANCEL      -> VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_DISCARD)
            else                       -> VoiceMemoTransition(VoiceMemoPhase.HELD, VoiceMemoEffect.NONE)
        }
        VoiceMemoPhase.LOCKED -> when (event) {
            // Deliberate flow: stopping parks the take in the preview panel.
            VoiceMemoEvent.STOP_TAP,
            VoiceMemoEvent.CAP_REACHED -> VoiceMemoTransition(VoiceMemoPhase.PREVIEW, VoiceMemoEffect.STOP_PREVIEW)
            VoiceMemoEvent.CANCEL      -> VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.STOP_DISCARD)
            VoiceMemoEvent.RESTART     -> VoiceMemoTransition(VoiceMemoPhase.LOCKED, VoiceMemoEffect.RESTART_RECORDING)
            else                       -> VoiceMemoTransition(VoiceMemoPhase.LOCKED, VoiceMemoEffect.NONE)
        }
        VoiceMemoPhase.PREVIEW -> when (event) {
            VoiceMemoEvent.ATTACH  -> VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.ATTACH_PREVIEW)
            VoiceMemoEvent.RESTART -> VoiceMemoTransition(VoiceMemoPhase.LOCKED, VoiceMemoEffect.RESTART_RECORDING)
            VoiceMemoEvent.CANCEL  -> VoiceMemoTransition(VoiceMemoPhase.IDLE, VoiceMemoEffect.DISCARD_PREVIEW)
            else                   -> VoiceMemoTransition(VoiceMemoPhase.PREVIEW, VoiceMemoEffect.NONE)
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
