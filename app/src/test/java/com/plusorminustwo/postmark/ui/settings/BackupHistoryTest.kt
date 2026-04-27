package com.plusorminustwo.postmark.ui.settings

import org.junit.Assert.*
import org.junit.Test

class BackupHistoryTest {

    private fun makeFiles(count: Int): List<BackupFileInfo> =
        (1..count).map { i ->
            BackupFileInfo(
                name = "postmark_2024-0$i-01_0200.json",
                sizeKb = (i * 10).toLong(),
                modifiedAt = i * 1_000L
            )
        }

    @Test
    fun `non-empty list preserves insertion order`() {
        val files = makeFiles(3)
        assertEquals("postmark_2024-01-01_0200.json", files[0].name)
        assertEquals("postmark_2024-02-01_0200.json", files[1].name)
        assertEquals("postmark_2024-03-01_0200.json", files[2].name)
    }

    @Test
    fun `empty list has size zero`() {
        val files = emptyList<BackupFileInfo>()
        assertTrue(files.isEmpty())
    }

    @Test
    fun `BackupFileInfo exposes correct properties`() {
        val file = BackupFileInfo(name = "test.json", sizeKb = 42L, modifiedAt = 1_000_000L)
        assertEquals("test.json", file.name)
        assertEquals(42L, file.sizeKb)
        assertEquals(1_000_000L, file.modifiedAt)
    }

    @Test
    fun `formatBackupDate produces non-empty string for positive timestamp`() {
        val formatted = formatBackupDate(1_714_000_000_000L)
        assertTrue(formatted.isNotEmpty())
    }
}
