package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// ── SyncLogger ────────────────────────────────────────────────────────────────
/** Append-only log file stored in the app's private internal storage.
 *  Captures sync events, incoming message persist results, and exceptions so
 *  the user can review them after the fact in Dev Options → Sync log.
 *
 *  Thread-safe: all writes are `@Synchronized`.
 *  Size-bounded: trims to [MAX_LINES] lines when [MAX_BYTES] is exceeded. */
@Singleton
class SyncLogger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Log file lives alongside the app's other private data — never on external storage.
    private val logFile: File get() = File(context.filesDir, "sync_log.txt")

    // Timestamp format used for each log line.
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ── Public API ────────────────────────────────────────────────────────────

    /** Appends one line: "2026-05-06 14:32:01 [tag] message". */
    @Synchronized
    fun log(tag: String, message: String) {
        val ts   = sdf.format(Date())
        val line = "$ts [$tag] $message\n"
        try {
            logFile.appendText(line)
            trimIfNeeded()
        } catch (e: Exception) {
            // Never let the logger crash the app.
            Log.w("SyncLogger", "Failed to write log entry", e)
        }
    }

    /** Appends an error line. If [throwable] is supplied, its stack trace follows
     *  the message as additional indented lines. */
    @Synchronized
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val stackTrace = throwable?.stackTraceToString()?.let { "\n$it" } ?: ""
        val ts   = sdf.format(Date())
        val line = "$ts [ERROR/$tag] $message$stackTrace\n"
        try {
            logFile.appendText(line)
            trimIfNeeded()
        } catch (e: Exception) {
            Log.w("SyncLogger", "Failed to write error log entry", e)
        }
    }

    /** Returns the full log content as a string, or a placeholder if empty. */
    fun readLog(): String = try {
        if (logFile.exists()) logFile.readText().ifBlank { "(log is empty)" }
        else "(no log yet)"
    } catch (e: Exception) {
        "Error reading log: ${e.message}"
    }

    /** Deletes the log file entirely. */
    fun clearLog() {
        try { logFile.delete() } catch (_: Exception) {}
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    // Only trim when the file actually exceeds the size cap — keeps writes fast.
    private fun trimIfNeeded() {
        if (logFile.length() <= MAX_BYTES) return
        val lines = logFile.readLines()
        if (lines.size > MAX_LINES) {
            // Keep the most recent entries.
            logFile.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
        }
    }

    private companion object {
        // ~100 KB cap; each average line is ~80 chars, so this holds ~1 250 lines.
        const val MAX_BYTES = 100_000L
        // After trim, keep the most recent 400 lines so the cap isn't hit immediately again.
        const val MAX_LINES = 400
    }
}
