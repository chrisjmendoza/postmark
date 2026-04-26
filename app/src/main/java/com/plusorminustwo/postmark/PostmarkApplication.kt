package com.plusorminustwo.postmark

import android.app.Application
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
        // Register content observer so incremental SMS changes are picked up
        // while the app is running. The initial bulk sync is triggered by
        // MainActivity after permissions are granted.
        smsContentObserver.register()
    }
}
