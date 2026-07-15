package com.plusorminustwo.postmark.ui.settings

import androidx.work.WorkInfo

/** UI state of the restore pipeline, derived from [RestoreWorker]'s WorkInfo. */
sealed class RestoreStatus {
    /** No restore has run in this install (or WorkManager has pruned it). */
    data object None : RestoreStatus()

    /** A restore is queued or waiting for constraints. */
    data object Queued : RestoreStatus()

    /** A restore is running. [total] of 0 means the size isn't known yet. */
    data class Running(val phase: String, val done: Int, val total: Int) : RestoreStatus()

    /** The last restore finished. [message] is RestoreWorker's human-readable
     *  summary (success) or error description (failure). */
    data class Finished(val message: String, val success: Boolean) : RestoreStatus()
}

/**
 * Pure mapper from RestoreWorker/ExportWorker WorkInfo fields to a [RestoreStatus].
 * Mirrors [mapWorkInfoToStatus] for backups, and is unit-tested the same way.
 * The label parameters let the export flow reuse the same states with its own
 * fallback wording.
 */
fun mapRestoreStatus(
    state: WorkInfo.State?,
    phase: String?,
    done: Int,
    total: Int,
    statusMessage: String?,
    errorMessage: String?,
    runningFallback: String = "Restoring…",
    doneFallback: String = "Restore complete",
    failedFallback: String = "Restore failed",
    cancelledMessage: String = "Restore cancelled"
): RestoreStatus = when (state) {
    null -> RestoreStatus.None
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> RestoreStatus.Queued
    WorkInfo.State.RUNNING -> RestoreStatus.Running(phase ?: runningFallback, done, total)
    WorkInfo.State.SUCCEEDED -> RestoreStatus.Finished(statusMessage ?: doneFallback, true)
    WorkInfo.State.FAILED -> RestoreStatus.Finished(errorMessage ?: failedFallback, false)
    WorkInfo.State.CANCELLED -> RestoreStatus.Finished(cancelledMessage, false)
}
