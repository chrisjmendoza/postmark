package com.plusorminustwo.postmark.domain.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Streaming reader/writer for the v2 backup archive (a plain zip).
 *
 * Entry order is a format invariant: manifest.json, then attachments/<sha256>
 * blobs, then data.jsonl. Attachments-before-records is what makes single-pass
 * streaming restore possible with ZipInputStream (which can only read forward) —
 * by the time a message record referencing a blob is decoded, the blob is already
 * on disk.
 *
 * Both classes are push/pull style rather than callback style so callers can
 * suspend (DB reads/writes) between items, and both are plain-JVM so the whole
 * archive round-trips in unit tests.
 */
object BackupArchive {

    const val MANIFEST_ENTRY = "manifest.json"
    const val DATA_ENTRY = "data.jsonl"
    const val ATTACHMENT_PREFIX = "attachments/"

    /** SHA-256 of [input] (closed on return) as lowercase hex — the blob naming used
     *  throughout the archive. */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        input.use {
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Writes one archive to [out]. Call [writeManifest] first, then [writeAttachment]
 * for each blob, then [writeRecordLine] for each data.jsonl line (the data entry
 * opens on the first line and is finalized by [close], which also closes [out]).
 */
class BackupArchiveWriter(out: OutputStream) : Closeable {
    private val zip = ZipOutputStream(BufferedOutputStream(out))
    private var inDataEntry = false
    private val newline = '\n'.code

    fun writeManifest(manifestJson: String) {
        check(!inDataEntry) { "Manifest must precede records" }
        zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST_ENTRY))
        zip.write(manifestJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** Streams one blob (closes [source]). Must be called before the first record
     *  line — attachments-before-records is the format's ordering invariant. */
    fun writeAttachment(sha256: String, source: InputStream) {
        check(!inDataEntry) { "Attachments must precede records" }
        zip.putNextEntry(ZipEntry(BackupArchive.ATTACHMENT_PREFIX + sha256))
        source.use { it.copyTo(zip) }
        zip.closeEntry()
    }

    fun writeRecordLine(line: String) {
        if (!inDataEntry) {
            zip.putNextEntry(ZipEntry(BackupArchive.DATA_ENTRY))
            inDataEntry = true
        }
        zip.write(line.toByteArray(Charsets.UTF_8))
        zip.write(newline)
    }

    override fun close() {
        if (inDataEntry) zip.closeEntry()
        zip.close()
    }
}

/**
 * Pull-based archive reader: call [next] until it returns null. For
 * [Event.Attachment], consume [Event.Attachment.stream] (bounded to that blob)
 * before the following [next] call — or don't, to skip the blob entirely.
 * Unknown entries are skipped for forward compatibility. Closes [input] on [close].
 */
class BackupArchiveReader(input: InputStream) : Closeable {
    private val zip = ZipInputStream(BufferedInputStream(input))
    private var dataReader: BufferedReader? = null

    sealed interface Event {
        data class Manifest(val json: String) : Event
        data class Attachment(val sha256: String, val stream: InputStream) : Event
        data class RecordLine(val line: String) : Event
    }

    fun next(): Event? {
        // Inside data.jsonl: emit one line per call until the entry is exhausted.
        dataReader?.let { reader ->
            var line = reader.readLine()
            while (line != null && line.isBlank()) line = reader.readLine()
            if (line != null) return Event.RecordLine(line)
            dataReader = null
        }
        while (true) {
            val entry = zip.nextEntry ?: return null
            when {
                entry.name == BackupArchive.MANIFEST_ENTRY ->
                    return Event.Manifest(zip.readBytes().toString(Charsets.UTF_8))

                entry.name.startsWith(BackupArchive.ATTACHMENT_PREFIX) ->
                    return Event.Attachment(
                        sha256 = entry.name.removePrefix(BackupArchive.ATTACHMENT_PREFIX),
                        // ZipInputStream.read() reports EOF at the entry boundary, so
                        // handing out the zip stream itself is bounded to this blob.
                        stream = zip
                    )

                entry.name == BackupArchive.DATA_ENTRY -> {
                    dataReader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
                    return next()
                }
                // else: unknown entry from a future writer — skip.
            }
        }
    }

    override fun close() = zip.close()
}

/** Backup file format, detected from the first bytes of the file. */
enum class BackupFormat { V2_ZIP, V1_JSON, UNKNOWN }

/** Detects the format from the file's leading bytes: zip magic "PK" = v2; a `{`
 *  (allowing leading whitespace) = v1 JSON; anything else is not a backup. */
fun detectBackupFormat(header: ByteArray): BackupFormat {
    if (header.size >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
        return BackupFormat.V2_ZIP
    }
    val firstNonWhitespace = header.firstOrNull {
        it != ' '.code.toByte() && it != '\t'.code.toByte() &&
            it != '\n'.code.toByte() && it != '\r'.code.toByte() &&
            it != 0xEF.toByte() && it != 0xBB.toByte() && it != 0xBF.toByte() // UTF-8 BOM
    }
    return if (firstNonWhitespace == '{'.code.toByte()) BackupFormat.V1_JSON
    else BackupFormat.UNKNOWN
}
