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
        const val CHANNEL_SYNC         = "sync_service"

        // ── Notification grouping ─────────────────────────────────────────────────
        // GROUP_KEY_SMS is the Android notification group key that bundles all
        // per-thread SMS notifications together in the shade.
        // NOTIF_ID_SMS_SUMMARY is the fixed ID used for the InboxStyle summary
        // notification that sits above the group. Int.MIN_VALUE is chosen because
        // it is extremely unlikely to collide with any sender's hashCode().
        const val GROUP_KEY_SMS        = "com.plusorminustwo.postmark.SMS_GROUP"
        const val NOTIF_ID_SMS_SUMMARY = Int.MIN_VALUE
    }
}
