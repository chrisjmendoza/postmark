package com.plusorminustwo.postmark.ui.stats

/**
 * Pure math backing the hand-rolled Charts-style composables in `StatsScreen.kt`
 * (the sent/received doughnut, the emoji bar chart) — kept out of Compose so it's
 * testable with plain JUnit, no Robolectric / instrumentation needed.
 */

/** Total sweep of a full ring, in degrees. */
internal const val DOUGHNUT_FULL_SWEEP_DEGREES = 360f

/**
 * Splits a full ring into a (sent, received) pair of sweep angles (degrees) proportional
 * to [sent] and [received]. When both are zero (no messages yet) returns (0f, 0f) — the
 * caller draws an empty/placeholder ring rather than dividing by zero.
 */
internal fun doughnutSweeps(sent: Int, received: Int): Pair<Float, Float> {
    val total = sent + received
    if (total <= 0) return 0f to 0f
    val sentSweep = DOUGHNUT_FULL_SWEEP_DEGREES * sent / total
    // Received gets the remainder rather than its own multiplication, so the two sweeps
    // always sum to exactly 360° regardless of float rounding.
    return sentSweep to (DOUGHNUT_FULL_SWEEP_DEGREES - sentSweep)
}

/**
 * Fraction (0f–1f) of the longest bar that [count] should render as, for a horizontal or
 * vertical bar chart sized off the largest value in the set ([maxCount]). Guards the
 * degenerate all-zero case (returns 0f for every bar instead of NaN from a 0/0 divide).
 */
internal fun barFraction(count: Int, maxCount: Int): Float =
    if (maxCount <= 0) 0f else (count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
