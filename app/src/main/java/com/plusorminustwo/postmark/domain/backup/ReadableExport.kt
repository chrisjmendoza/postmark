package com.plusorminustwo.postmark.domain.backup

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Pure pieces of the readable ("text + media") export — the human-facing sibling of
 * the v2 backup archive. Layout of the produced zip:
 *
 *   README.txt
 *   <Conversation name>.txt              — ExportFormatter transcript per thread
 *   media/<Conversation name>/<file>     — attachments with date-stamped, extension-
 *                                          correct names any gallery/file manager opens
 *
 * Everything here is plain JVM so naming rules and the zip layout are unit-tested;
 * the Android side (queries, ContentResolver reads) lives in ReadableExportWriter.
 */

// Characters that break zip entries or extraction on common filesystems, plus controls.
private val ILLEGAL_FILE_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
private val WHITESPACE_RUNS = Regex("""\s+""")

/**
 * Makes a conversation display name safe to use as a file/folder name inside the zip:
 * strips characters that are illegal on Windows/Android filesystems, collapses
 * whitespace, trims leading/trailing dots (Windows rejects them), and caps length.
 * Falls back to [fallback] when nothing printable survives.
 */
fun sanitizeForFileName(raw: String, fallback: String = "Conversation"): String {
    val cleaned = raw
        .replace(ILLEGAL_FILE_CHARS, " ")
        .replace(WHITESPACE_RUNS, " ")
        .trim()
        .take(80)
        .trim(' ', '.')
    return cleaned.ifEmpty { fallback }
}

/**
 * Returns [base] made unique against [taken] by appending " (2)", " (3)", … and
 * registers the result. Case-insensitive: the zip may be extracted onto a
 * case-insensitive filesystem where "Mom" and "MOM" collide.
 */
fun uniqueName(base: String, taken: MutableSet<String>): String {
    var candidate = base
    var n = 2
    while (!taken.add(candidate.lowercase())) {
        candidate = "$base (${n++})"
    }
    return candidate
}

/**
 * "base.ext", made unique against [taken] with a " (2)" suffix *before* the
 * extension so files keep opening in the right app. Registers the result.
 */
fun uniqueFileName(base: String, extension: String, taken: MutableSet<String>): String {
    var candidate = "$base.$extension"
    var n = 2
    while (!taken.add(candidate.lowercase())) {
        candidate = "$base (${n++}).$extension"
    }
    return candidate
}

/**
 * File extension for an attachment MIME type — the piece that makes exported media
 * open on double-click. Unknown types fall back to "bin" rather than guessing.
 */
fun extensionForMime(mimeType: String?): String =
    when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/heic", "image/heif" -> "heic"
        "video/mp4" -> "mp4"
        "video/3gpp", "video/3gp" -> "3gp"
        "video/webm" -> "webm"
        "audio/amr" -> "amr"
        "audio/3gpp" -> "3gp"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/mp4", "audio/aac", "audio/m4a" -> "m4a"
        else -> "bin"
    }

private val MEDIA_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

/** "2026-05-01_1432" — the date-stamped base name for an exported attachment. */
fun mediaBaseName(timestampMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    MEDIA_NAME_FORMATTER.format(Instant.ofEpochMilli(timestampMs).atZone(zone))

/**
 * Minimal zip writer for the readable export: text entries and streamed media
 * entries, no format invariants beyond "write whatever, in any order". Closes
 * [out] on [close].
 */
class ReadableArchiveWriter(out: OutputStream) : Closeable {
    private val zip = ZipOutputStream(BufferedOutputStream(out))

    fun writeText(entryPath: String, text: String) {
        zip.putNextEntry(ZipEntry(entryPath))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** Streams one media file (closes [source]). */
    fun writeStream(entryPath: String, source: InputStream) {
        zip.putNextEntry(ZipEntry(entryPath))
        source.use { it.copyTo(zip) }
        zip.closeEntry()
    }

    override fun close() = zip.close()
}
