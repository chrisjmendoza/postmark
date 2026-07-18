package com.plusorminustwo.postmark.domain.selection

/**
 * Decides the target state a bulk pin/mute action should apply to every selected thread.
 *
 * Apply-to-all rule (owner spec): if ANY selected thread is currently *off* (unpinned /
 * unmuted) the action turns them ALL on; only when every selected thread is already on
 * does the action turn them all off. So the button reads "Pin all" until nothing is left
 * to pin, then flips to "Unpin all". The same rule drives mute/unmute.
 *
 * @param currentFlags the current isPinned (or isMuted) value of each selected thread.
 * @return the value to write to all of them: `true` = turn on (pin/mute all),
 *         `false` = turn off (unpin/unmute all). An empty selection returns `false`.
 */
fun bulkToggleTarget(currentFlags: List<Boolean>): Boolean = currentFlags.any { !it }
