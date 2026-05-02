package com.plusorminustwo.postmark

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.plusorminustwo.postmark.data.sync.SmsContentObserver
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
        createNotificationChannels()
        // Register content observer so incremental SMS changes are picked up
        // while the app is running. The initial bulk sync is triggered by
        // MainActivity after permissions are granted.
        smsContentObserver.register()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING_SMS,
                "Incoming messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New SMS messages"
                enableLights(true)
                enableVibration(true)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                "Background sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SMS sync progress"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_INCOMING_SMS = "incoming_sms"
        const val CHANNEL_SYNC = "sync_service"
    }
}
