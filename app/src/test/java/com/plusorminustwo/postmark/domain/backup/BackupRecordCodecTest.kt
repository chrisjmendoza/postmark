package com.plusorminustwo.postmark.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRecordCodecTest {

    private val fullThread = ThreadRecord(
        address = "+12065551234",
        displayName = "Alice, Bob",
        nickname = "The Crew",
        isPinned = true,
        isMuted = true,
        notificationsEnabled = false,
        backupPolicy = "ALWAYS_INCLUDE",
        participants = listOf("+12065551234", "+12065555678"),
        lastMessageAt = 1_752_000_000_000L,
        lastMessagePreview = "see you\nthere",
        isSpam = true
    )

    private val fullMessage = MessageRecord(
        address = "+12065551234",
        body = "photo attached\nsecond line",
        timestamp = 1_752_000_000_123L,
        isSent = false,
        type = 1,
        deliveryStatus = 3,
        isMms = true,
        isRead = false,
        isStarred = true,
        isPinned = true,
        sentAt = 1_752_000_000_050L,
        deliveredAt = 1_752_000_000_200L,
        attachments = listOf(AttachmentRef("ab12cd", "image/jpeg")),
        reactions = listOf(
            ReactionRecord("self", "❤️", 1_752_000_100_000L, ""),
            ReactionRecord("+12065555678", "👍", 1_752_000_200_000L, "Liked \"photo\"")
        )
    )

    @Test
    fun `thread line round trips`() {
        val line = encodeThreadLine(fullThread)
        assertTrue(line.none { it == '\n' })
        val decoded = decodeBackupLine(line) as BackupLine.ThreadStart
        assertEquals(fullThread, decoded.thread)
    }

    @Test
    fun `message line round trips with attachments and reactions`() {
        val line = encodeMessageLine(fullMessage)
        assertTrue(line.none { it == '\n' })
        val decoded = decodeBackupLine(line) as BackupLine.Msg
        assertEquals(fullMessage, decoded.message)
    }

    @Test
    fun `manifest round trips`() {
        val manifest = BackupManifest(
            version = 2,
            exportedAt = 1_752_000_000_000L,
            threadCount = 620,
            messageCount = 159_000,
            attachmentCount = 4_200
        )
        assertEquals(manifest, decodeManifest(encodeManifest(manifest)))
    }

    @Test
    fun `manifest decode returns null for garbage`() {
        assertNull(decodeManifest("not json"))
        assertNull(decodeManifest("[1,2,3]"))
        assertNull(decodeManifest("""{"exportedAt":5}""")) // no version
    }

    @Test
    fun `blank and unknown record lines decode to null`() {
        assertNull(decodeBackupLine(""))
        assertNull(decodeBackupLine("   "))
        // Forward compatibility: a future record type is skipped, not fatal.
        assertNull(decodeBackupLine("""{"t":"hologram","x":1}"""))
    }

    @Test
    fun `message decode fills defaults for missing optional fields`() {
        val decoded = decodeBackupLine(
            """{"t":"msg","address":"555","body":"hi","timestamp":42,"isSent":true}"""
        ) as BackupLine.Msg
        val msg = decoded.message
        assertEquals(2, msg.type) // synthesized from isSent
        assertEquals(0, msg.deliveryStatus)
        assertEquals(false, msg.isMms)
        assertEquals(true, msg.isRead)
        assertEquals(false, msg.isStarred)
        assertEquals(false, msg.isPinned)
        assertNull(msg.sentAt)
        assertNull(msg.deliveredAt)
        assertTrue(msg.attachments.isEmpty())
        assertTrue(msg.reactions.isEmpty())
    }

    @Test
    fun `thread decode fills defaults and requires address`() {
        val decoded = decodeBackupLine("""{"t":"thread","address":"555"}""")
            as BackupLine.ThreadStart
        val t = decoded.thread
        assertEquals("GLOBAL", t.backupPolicy)
        assertEquals(true, t.notificationsEnabled)
        assertEquals(false, t.isSpam)
        assertNull(t.nickname)
        assertTrue(t.participants.isEmpty())
        try {
            decodeBackupLine("""{"t":"thread","displayName":"no address"}""")
            org.junit.Assert.fail("Expected failure for thread without address")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // ---- v1 ----

    @Test
    fun `parses v1 document and synthesizes missing fields`() {
        val v1 = """
            {
              "version": 1,
              "exportedAt": 1751000000000,
              "threads": [
                {
                  "id": 7,
                  "displayName": "Alice",
                  "address": "+12065551234",
                  "messages": [
                    { "id": 100, "body": "hello", "timestamp": 1000, "isSent": false },
                    { "id": 101, "body": "hi back", "timestamp": 2000, "isSent": true }
                  ]
                }
              ]
            }
        """.trimIndent()
        val threads = parseV1Backup(v1)
        assertEquals(1, threads.size)
        val (thread, messages) = threads[0]
        assertEquals("+12065551234", thread.address)
        assertEquals("Alice", thread.displayName)
        assertEquals(2000L, thread.lastMessageAt)
        assertEquals("hi back", thread.lastMessagePreview)
        assertEquals(2, messages.size)
        // Synthesized: address from thread, type from isSent, SMS, read, no media.
        assertEquals("+12065551234", messages[0].address)
        assertEquals(1, messages[0].type)
        assertEquals(2, messages[1].type)
        assertTrue(messages.all { !it.isMms && it.isRead && it.attachments.isEmpty() })
    }

    @Test
    fun `v1 parse skips malformed entries but keeps good ones`() {
        val v1 = """
            {"version":1,"threads":[
              {"displayName":"no address","messages":[]},
              {"address":"555","messages":[{"body":"ok","timestamp":1,"isSent":false},{"body":"no timestamp"}]}
            ]}
        """.trimIndent()
        val threads = parseV1Backup(v1)
        assertEquals(1, threads.size)
        assertEquals(1, threads[0].messages.size)
        assertEquals("ok", threads[0].messages[0].body)
    }
}
