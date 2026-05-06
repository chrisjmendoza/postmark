package com.plusorminustwo.postmark.service.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [CoroutineWorker] that serialises all threads (with their messages) that have
 * [BackupPolicy.ENABLED] to a JSON file in the app's external `backups/` directory.
 *
 * Retries up to 3 times on failure, recording success or failure via
 * `SharedPreferences` so [BackupSettingsViewModel] can show the last run status.
 * Scheduled and cancelled by [BackupScheduler]; triggered on-demand by
 * [BackupSettingsViewModel.runNow].
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val path = performBackup()
            val prefs = applicationContext.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("last_backup_timestamp", System.currentTimeMillis())
                .putString("last_backup_status", "SUCCESS")
                .putString("last_backup_path", path)
                .apply()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                val prefs = applicationContext.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("last_backup_timestamp", System.currentTimeMillis())
                    .putString("last_backup_status", "FAILED")
                    .apply()
                Result.failure()
            }
        }
    }

    private suspend fun performBackup(): String {
        val threads = threadRepository.getThreadsForBackup()
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val threadsArray = JSONArray()
        threads.forEach { thread ->
            val messages = messageRepository.getByThread(thread.id)
            threadsArray.put(serializeThread(thread, messages))
        }
        root.put("threads", threadsArray)

        val dir = applicationContext.getExternalFilesDir("backups") ?: return ""
        pruneOldBackups(dir)

        val filename = "postmark_${dateStamp()}.json"
        val file = File(dir, filename)
        file.writeText(root.toString(2))
        return file.absolutePath
    }

    private fun serializeThread(thread: Thread, messages: List<Message>): JSONObject {
        val obj = JSONObject()
        obj.put("id", thread.id)
        obj.put("displayName", thread.displayName)
        obj.put("address", thread.address)

        val msgsArray = JSONArray()
        messages.forEach { msg ->
            val m = JSONObject()
            m.put("id", msg.id)
            m.put("body", msg.body)
            m.put("timestamp", msg.timestamp)
            m.put("isSent", msg.isSent)
            msgsArray.put(m)
        }
        obj.put("messages", msgsArray)
        return obj
    }

    private fun pruneOldBackups(dir: File) {
        val prefs = applicationContext.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
        val retention = prefs.getInt("retention_count", 5)
        val files = dir.listFiles { f -> f.name.startsWith("postmark_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(retention - 1).forEach { it.delete() }
    }

    private fun dateStamp(): String =
        SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())

    companion object {
        const val WORK_NAME = "postmark_backup"
    }
}
