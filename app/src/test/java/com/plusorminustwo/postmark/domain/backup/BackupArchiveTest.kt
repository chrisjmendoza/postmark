package com.plusorminustwo.postmark.domain.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveTest {

    private val manifestJson = encodeManifest(
        BackupManifest(2, 1_752_000_000_000L, 1, 2, 2)
    )
    private val blobA = "fake jpeg bytes".toByteArray()
    private val blobB = ByteArray(70_000) { (it % 251).toByte() } // multi-buffer copy
    private val shaA = BackupArchive.sha256Hex(ByteArrayInputStream(blobA))
    private val shaB = BackupArchive.sha256Hex(ByteArrayInputStream(blobB))
    private val lines = listOf(
        """{"t":"thread","address":"555"}""",
        """{"t":"msg","address":"555","body":"hi","timestamp":1}"""
    )

    private fun writeArchive(): ByteArray {
        val out = ByteArrayOutputStream()
        BackupArchiveWriter(out).use { writer ->
            writer.writeManifest(manifestJson)
            writer.writeAttachment(shaA, ByteArrayInputStream(blobA))
            writer.writeAttachment(shaB, ByteArrayInputStream(blobB))
            lines.forEach { writer.writeRecordLine(it) }
        }
        return out.toByteArray()
    }

    /** Drains a reader into (manifest, blobs, lines, eventOrder). */
    private fun drain(
        bytes: ByteArray,
        consumeBlobs: Boolean = true
    ): Triple<String?, Map<String, ByteArray>, List<String>> {
        var manifest: String? = null
        val blobs = mutableMapOf<String, ByteArray>()
        val readLines = mutableListOf<String>()
        BackupArchiveReader(ByteArrayInputStream(bytes)).use { reader ->
            while (true) {
                when (val event = reader.next() ?: break) {
                    is BackupArchiveReader.Event.Manifest -> manifest = event.json
                    is BackupArchiveReader.Event.Attachment ->
                        if (consumeBlobs) blobs[event.sha256] = event.stream.readBytes()
                    is BackupArchiveReader.Event.RecordLine -> readLines += event.line
                }
            }
        }
        return Triple(manifest, blobs, readLines)
    }

    @Test
    fun `full archive round trips`() {
        val (manifest, blobs, readLines) = drain(writeArchive())
        assertEquals(manifestJson, manifest)
        assertEquals(2, blobs.size)
        assertTrue(blobA.contentEquals(blobs[shaA]))
        assertTrue(blobB.contentEquals(blobs[shaB]))
        assertEquals(lines, readLines)
    }

    @Test
    fun `events arrive in manifest-attachments-records order`() {
        val order = mutableListOf<String>()
        BackupArchiveReader(ByteArrayInputStream(writeArchive())).use { reader ->
            while (true) {
                when (reader.next() ?: break) {
                    is BackupArchiveReader.Event.Manifest -> order += "manifest"
                    is BackupArchiveReader.Event.Attachment -> order += "blob"
                    is BackupArchiveReader.Event.RecordLine -> order += "line"
                }
            }
        }
        assertEquals(listOf("manifest", "blob", "blob", "line", "line"), order)
    }

    @Test
    fun `unconsumed attachment streams do not break subsequent reads`() {
        // Restore skips blobs it already has without draining them.
        val (manifest, blobs, readLines) = drain(writeArchive(), consumeBlobs = false)
        assertEquals(manifestJson, manifest)
        assertTrue(blobs.isEmpty())
        assertEquals(lines, readLines)
    }

    @Test
    fun `record lines with multibyte content survive the stream`() {
        val emoji = """{"t":"msg","address":"555","body":"👍❤️","timestamp":1}"""
        val out = ByteArrayOutputStream()
        BackupArchiveWriter(out).use { writer ->
            writer.writeManifest(manifestJson)
            writer.writeRecordLine(emoji)
        }
        val (_, _, readLines) = drain(out.toByteArray())
        assertEquals(listOf(emoji), readLines)
    }

    @Test
    fun `writer rejects attachments after records`() {
        val out = ByteArrayOutputStream()
        BackupArchiveWriter(out).use { writer ->
            writer.writeManifest(manifestJson)
            writer.writeRecordLine(lines[0])
            try {
                writer.writeAttachment(shaA, ByteArrayInputStream(blobA))
                org.junit.Assert.fail("Expected ordering violation to throw")
            } catch (expected: IllegalStateException) {
                // expected — attachments-before-records is a format invariant
            }
        }
    }

    @Test
    fun `sha256Hex matches known vector`() {
        // SHA-256("abc") — standard test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BackupArchive.sha256Hex(ByteArrayInputStream("abc".toByteArray()))
        )
    }

    @Test
    fun `read tolerates archive with unknown extra entries`() {
        // Forward compatibility: a future writer may add entries; readers skip them.
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(BackupArchive.MANIFEST_ENTRY))
            zip.write(manifestJson.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("future/mystery.bin"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry(BackupArchive.DATA_ENTRY))
            zip.write(lines[0].toByteArray())
            zip.closeEntry()
        }
        val (manifest, blobs, readLines) = drain(out.toByteArray())
        assertEquals(manifestJson, manifest)
        assertTrue(blobs.isEmpty())
        assertEquals(listOf(lines[0]), readLines)
    }

    @Test
    fun `empty archive with manifest only reads cleanly`() {
        val out = ByteArrayOutputStream()
        BackupArchiveWriter(out).use { it.writeManifest(manifestJson) }
        val (manifest, blobs, readLines) = drain(out.toByteArray())
        assertEquals(manifestJson, manifest)
        assertTrue(blobs.isEmpty())
        assertTrue(readLines.isEmpty())
    }

    // ---- format detection ----

    @Test
    fun `detects v2 zip by magic bytes`() {
        assertEquals(BackupFormat.V2_ZIP, detectBackupFormat(writeArchive().copyOf(4)))
    }

    @Test
    fun `detects v1 json with optional whitespace and BOM`() {
        assertEquals(BackupFormat.V1_JSON, detectBackupFormat("{\"version\":1".toByteArray()))
        assertEquals(BackupFormat.V1_JSON, detectBackupFormat("  \n {".toByteArray()))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{".toByteArray()
        assertEquals(BackupFormat.V1_JSON, detectBackupFormat(bom))
    }

    @Test
    fun `unknown formats are rejected`() {
        assertEquals(BackupFormat.UNKNOWN, detectBackupFormat("hello".toByteArray()))
        assertEquals(BackupFormat.UNKNOWN, detectBackupFormat(ByteArray(0)))
        assertNull(decodeManifest("PK"))
    }
}
