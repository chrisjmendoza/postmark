package com.plusorminustwo.postmark.domain.backup

import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.RESTORED_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.Thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreMergeTest {

    // ---- normalizeAddress ----

    @Test
    fun `real numbers match across formatting differences`() {
        val forms = listOf("+12065551234", "12065551234", "2065551234", "(206) 555-1234", "206-555-1234")
        val normalized = forms.map { normalizeAddress(it) }.distinct()
        assertEquals(listOf("2065551234"), normalized)
    }

    @Test
    fun `short codes keep their digits`() {
        assertEquals("87892", normalizeAddress("87892"))
        assertEquals("87892", normalizeAddress(" 878-92 "))
    }

    @Test
    fun `alphanumeric senders compare case-insensitively`() {
        assertEquals(normalizeAddress("AMAZON"), normalizeAddress("amazon"))
        assertNotEquals(normalizeAddress("AMAZON"), normalizeAddress("VERIZON"))
    }

    @Test
    fun `different real numbers stay distinct`() {
        assertNotEquals(normalizeAddress("+12065551234"), normalizeAddress("+12065559999"))
    }

    // ---- fingerprint + index ----

    @Test
    fun `fingerprint is stable across address formatting`() {
        val a = messageFingerprint("+12065551234", 1000L, false, false, "hi")
        val b = messageFingerprint("206-555-1234", 1000L, false, false, "hi")
        assertEquals(a, b)
    }

    @Test
    fun `fingerprint distinguishes direction transport timestamp and body`() {
        val base = messageFingerprint("555", 1000L, false, false, "hi")
        assertNotEquals(base, messageFingerprint("555", 1000L, true, false, "hi"))
        assertNotEquals(base, messageFingerprint("555", 1000L, false, true, "hi"))
        assertNotEquals(base, messageFingerprint("555", 1001L, false, false, "hi"))
        assertNotEquals(base, messageFingerprint("555", 1000L, false, false, "hi!"))
    }

    @Test
    fun `index consumes one local copy per match`() {
        val index = RestoreMessageIndex()
        val fp = messageFingerprint("555", 1000L, false, false, "dup")
        index.add(fp, 11L)
        index.add(fp, 12L) // genuine duplicate exists locally too

        assertEquals(11L, index.consumeMatch(fp))
        assertEquals(12L, index.consumeMatch(fp))
        // Third backup copy has no local counterpart left -> would be inserted.
        assertNull(index.consumeMatch(fp))
    }

    @Test
    fun `index misses unknown fingerprints`() {
        val index = RestoreMessageIndex()
        index.add(messageFingerprint("555", 1L, false, false, "a"), 1L)
        assertNull(index.consumeMatch(messageFingerprint("555", 2L, false, false, "a")))
    }

    // ---- thread metadata merge ----

    private val defaultLocal = Thread(
        id = 42L,
        displayName = "Alice",
        address = "+12065551234",
        lastMessageAt = 0L
    )

    private val richRecord = ThreadRecord(
        address = "+12065551234",
        displayName = "Alice",
        nickname = "Ali",
        isPinned = true,
        isMuted = true,
        notificationsEnabled = false,
        backupPolicy = "NEVER_INCLUDE",
        participants = emptyList(),
        lastMessageAt = 9000L,
        lastMessagePreview = "yo"
    )

    @Test
    fun `backup metadata fills local defaults`() {
        val updates = mergeThreadMetadata(defaultLocal, richRecord)
        assertEquals("Ali", updates.nickname)
        assertEquals(true, updates.isPinned)
        assertEquals(true, updates.isMuted)
        assertEquals(false, updates.notificationsEnabled)
        assertEquals(BackupPolicy.NEVER_INCLUDE, updates.backupPolicy)
        assertFalse(updates.isEmpty)
    }

    @Test
    fun `local non-default values are never clobbered`() {
        val customized = defaultLocal.copy(
            nickname = "MyName",
            isPinned = true,
            isMuted = true,
            notificationsEnabled = false,
            backupPolicy = BackupPolicy.ALWAYS_INCLUDE
        )
        val updates = mergeThreadMetadata(customized, richRecord)
        assertTrue(updates.isEmpty)
    }

    @Test
    fun `default backup values change nothing`() {
        val plainRecord = richRecord.copy(
            nickname = null,
            isPinned = false,
            isMuted = false,
            notificationsEnabled = true,
            backupPolicy = "GLOBAL"
        )
        assertTrue(mergeThreadMetadata(defaultLocal, plainRecord).isEmpty)
    }

    @Test
    fun `restore never unsets a local pin with a backup unpin`() {
        val pinnedLocal = defaultLocal.copy(isPinned = true)
        val unpinnedRecord = richRecord.copy(isPinned = false)
        assertNull(mergeThreadMetadata(pinnedLocal, unpinnedRecord).isPinned)
    }

    @Test
    fun `unknown backup policy names fall back to GLOBAL`() {
        assertEquals(BackupPolicy.GLOBAL, decodeBackupPolicy("FUTURE_POLICY"))
        assertEquals(BackupPolicy.NEVER_INCLUDE, decodeBackupPolicy("NEVER_INCLUDE"))
    }

    // ---- sanitizeForRestore ----

    private val record = MessageRecord(
        address = "555", body = "b", timestamp = 1L, isSent = true, type = 2,
        deliveryStatus = 0, isMms = false, isRead = false, isStarred = false,
        attachments = emptyList(), reactions = emptyList()
    )

    @Test
    fun `pending and failed statuses normalize to none`() {
        assertEquals(0, sanitizeForRestore(record.copy(deliveryStatus = 1)).deliveryStatus)
        assertEquals(0, sanitizeForRestore(record.copy(deliveryStatus = 4)).deliveryStatus)
    }

    @Test
    fun `sent and delivered statuses are preserved`() {
        assertEquals(2, sanitizeForRestore(record.copy(deliveryStatus = 2)).deliveryStatus)
        assertEquals(3, sanitizeForRestore(record.copy(deliveryStatus = 3)).deliveryStatus)
    }

    @Test
    fun `restored messages are always read`() {
        assertTrue(sanitizeForRestore(record.copy(isRead = false)).isRead)
    }

    // ---- restored id allocation ----

    @Test
    fun `first restore starts at the offset`() {
        assertEquals(RESTORED_ID_OFFSET, firstRestoredId(null))
    }

    @Test
    fun `subsequent restores continue after existing restored rows`() {
        assertEquals(RESTORED_ID_OFFSET + 501, firstRestoredId(RESTORED_ID_OFFSET + 500))
    }

    @Test
    fun `garbage max below the offset still yields the offset`() {
        assertEquals(RESTORED_ID_OFFSET, firstRestoredId(123L))
    }

    // ---- filename filter ----

    @Test
    fun `v1 json and v2 zip share the retention pool`() {
        assertTrue(isBackupFileName("postmark_2026-07-11_0200.json"))
        assertTrue(isBackupFileName("postmark_2026-07-11_0200.zip"))
        assertFalse(isBackupFileName("postmark_2026-07-11_0200.zip.tmp"))
        assertFalse(isBackupFileName("other.zip"))
        assertFalse(isBackupFileName("notes.json"))
    }

    @Test
    fun `user exports are never part of the retention pool`() {
        // An export saved into the backup folder must not be eaten by pruning.
        assertFalse(isBackupFileName("postmark_export_2026-07-12_1030.zip"))
        assertFalse(isBackupFileName("postmark_export_2026-07-12_1030.json"))
        // ...while regular backups still are.
        assertTrue(isBackupFileName("postmark_2026-07-12_1030.zip"))
    }

    // ---- fallback thread id ----

    @Test
    fun `fallback thread ids are negative stable and format-insensitive`() {
        val a = fallbackThreadId("+12065551234")
        assertTrue(a < 0)
        assertEquals(a, fallbackThreadId("206-555-1234"))
        assertNotEquals(a, fallbackThreadId("+12065559999"))
    }
}
