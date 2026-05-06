package com.plusorminustwo.postmark.ui.settings

import androidx.work.WorkInfo

/** Represents the current state of the periodic backup job for display in the UI. */
sealed class BackupStatus {
    data object Idle : BackupStatus()
    data object Running : BackupStatus()
    data class LastRun(val timestamp: Long, val success: Boolean) : BackupStatus()
    data object Never : BackupStatus()
}

internal fun mapWorkInfoToStatus(state: WorkInfo.State?, lastTimestamp: Long): BackupStatus =
    when (state) {
        null -> BackupStatus.Never
        WorkInfo.State.RUNNING -> BackupStatus.Running
        WorkInfo.State.SUCCEEDED -> BackupStatus.LastRun(lastTimestamp, true)
        WorkInfo.State.FAILED -> BackupStatus.LastRun(lastTimestamp, false)
        else -> if (lastTimestamp > 0L) BackupStatus.LastRun(lastTimestamp, true)
                else BackupStatus.Idle
    }
