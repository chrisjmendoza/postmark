package com.plusorminustwo.postmark

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.plusorminustwo.postmark.data.sync.FirstLaunchSyncWorker
import com.plusorminustwo.postmark.data.sync.SmsContentObserver
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PostmarkApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var smsContentObserver: SmsContentObserver

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        smsContentObserver.register()
        scheduleFirstLaunchSyncIfNeeded()
    }

    private fun scheduleFirstLaunchSyncIfNeeded() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("first_launch_sync_done", false)) return

        WorkManager.getInstance(this)
            .enqueue(FirstLaunchSyncWorker.buildRequest())

        prefs.edit().putBoolean("first_launch_sync_done", true).apply()
    }
}
