package com.plusorminustwo.postmark.service.backup

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** How often automatic backups run. The interval is approximate — WorkManager
 *  may run the job up to 5 minutes late on battery-optimised devices. */
enum class BackupFrequency { DAILY, WEEKLY, MONTHLY }

/**
 * Schedules and cancels the recurring [BackupWorker] via WorkManager.
 *
 * Call [schedule] from the Settings screen whenever the user changes backup
 * preferences; call [cancel] when the user disables automatic backups entirely.
 * [runNow] enqueues a one-off backup regardless of the periodic schedule.
 */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Cancels any existing periodic backup work and re-schedules with the given [frequency]
     *  and optional timing/constraint parameters. */
    fun schedule(
        frequency: BackupFrequency,
        hourOfDay: Int = 2,
        minuteOfHour: Int = 0,
        requireWifi: Boolean = true,
        requireCharging: Boolean = true,
        dayOfWeek: Int = Calendar.SUNDAY,   // for WEEKLY
        dayOfMonth: Int = 1                 // for MONTHLY
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requireWifi) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .setRequiresCharging(requireCharging)
            .build()

        val intervalMs = when (frequency) {
            BackupFrequency.DAILY -> TimeUnit.DAYS.toMillis(1)
            BackupFrequency.WEEKLY -> TimeUnit.DAYS.toMillis(7)
            BackupFrequency.MONTHLY -> TimeUnit.DAYS.toMillis(30)
        }
        val intervalMinutes = intervalMs / (60 * 1000)

        val initialDelayMs = calculateInitialDelay(
            frequency, hourOfDay, minuteOfHour, dayOfWeek, dayOfMonth
        )

        val request = PeriodicWorkRequestBuilder<BackupWorker>(
            intervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Cancels the periodic backup work. The next [schedule] call re-enables it. */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(BackupWorker.WORK_NAME)
    }

    /** Enqueues a one-off backup immediately, independent of the periodic schedule. */
    fun runNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun calculateInitialDelay(
        frequency: BackupFrequency,
        hourOfDay: Int,
        minuteOfHour: Int,
        dayOfWeek: Int,
        dayOfMonth: Int
    ): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minuteOfHour)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            when (frequency) {
                BackupFrequency.WEEKLY -> {
                    set(Calendar.DAY_OF_WEEK, dayOfWeek)
                    if (before(now)) add(Calendar.WEEK_OF_YEAR, 1)
                }
                BackupFrequency.MONTHLY -> {
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    if (before(now)) add(Calendar.MONTH, 1)
                }
                BackupFrequency.DAILY -> {
                    if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
        return maxOf(0L, target.timeInMillis - now.timeInMillis)
    }
}
