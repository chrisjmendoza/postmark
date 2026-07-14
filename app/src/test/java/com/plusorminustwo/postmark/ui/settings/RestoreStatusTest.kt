package com.plusorminustwo.postmark.ui.settings

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreStatusTest {

    @Test
    fun `no work info means no restore has run`() {
        assertEquals(RestoreStatus.None, mapRestoreStatus(null, null, 0, 0, null, null))
    }

    @Test
    fun `enqueued and blocked map to queued`() {
        assertEquals(RestoreStatus.Queued, mapRestoreStatus(WorkInfo.State.ENQUEUED, null, 0, 0, null, null))
        assertEquals(RestoreStatus.Queued, mapRestoreStatus(WorkInfo.State.BLOCKED, null, 0, 0, null, null))
    }

    @Test
    fun `running carries phase and progress`() {
        assertEquals(
            RestoreStatus.Running("Restoring messages", 400, 1000),
            mapRestoreStatus(WorkInfo.State.RUNNING, "Restoring messages", 400, 1000, null, null)
        )
    }

    @Test
    fun `running without phase falls back to generic label`() {
        assertEquals(
            RestoreStatus.Running("Restoring…", 0, 0),
            mapRestoreStatus(WorkInfo.State.RUNNING, null, 0, 0, null, null)
        )
    }

    @Test
    fun `success and failure carry the worker's message`() {
        assertEquals(
            RestoreStatus.Finished("Restored 12 messages (3 already present)", true),
            mapRestoreStatus(WorkInfo.State.SUCCEEDED, null, 0, 0, "Restored 12 messages (3 already present)", null)
        )
        assertEquals(
            RestoreStatus.Finished("Not a Postmark backup file", false),
            mapRestoreStatus(WorkInfo.State.FAILED, null, 0, 0, null, "Not a Postmark backup file")
        )
    }

    @Test
    fun `cancelled counts as an unsuccessful finish`() {
        assertEquals(
            RestoreStatus.Finished("Restore cancelled", false),
            mapRestoreStatus(WorkInfo.State.CANCELLED, null, 0, 0, null, null)
        )
    }
}
