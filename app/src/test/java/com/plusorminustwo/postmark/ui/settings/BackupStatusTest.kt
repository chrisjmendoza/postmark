package com.plusorminustwo.postmark.ui.settings

import androidx.work.WorkInfo
import org.junit.Assert.*
import org.junit.Test

class BackupStatusTest {

    @Test
    fun `null state produces Never`() {
        assertEquals(BackupStatus.Never, mapWorkInfoToStatus(null, 0L))
    }

    @Test
    fun `RUNNING state produces Running`() {
        assertEquals(BackupStatus.Running, mapWorkInfoToStatus(WorkInfo.State.RUNNING, 0L))
    }

    @Test
    fun `SUCCEEDED state with timestamp produces LastRun success=true`() {
        val result = mapWorkInfoToStatus(WorkInfo.State.SUCCEEDED, 12345L)
        assertEquals(BackupStatus.LastRun(12345L, true), result)
    }

    @Test
    fun `FAILED state with timestamp produces LastRun success=false`() {
        val result = mapWorkInfoToStatus(WorkInfo.State.FAILED, 99999L)
        assertEquals(BackupStatus.LastRun(99999L, false), result)
    }

    @Test
    fun `ENQUEUED state with no prior backup produces Idle`() {
        assertEquals(BackupStatus.Idle, mapWorkInfoToStatus(WorkInfo.State.ENQUEUED, 0L))
    }

    @Test
    fun `ENQUEUED state with prior backup timestamp produces LastRun success=true`() {
        val result = mapWorkInfoToStatus(WorkInfo.State.ENQUEUED, 55555L)
        assertEquals(BackupStatus.LastRun(55555L, true), result)
    }

    @Test
    fun `BLOCKED state with no prior backup produces Idle`() {
        assertEquals(BackupStatus.Idle, mapWorkInfoToStatus(WorkInfo.State.BLOCKED, 0L))
    }
}
