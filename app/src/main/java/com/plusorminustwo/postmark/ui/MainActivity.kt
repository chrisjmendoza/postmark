package com.plusorminustwo.postmark.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.metrics.performance.JankStats
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.plusorminustwo.postmark.data.sync.SmsHistoryImportWorker
import com.plusorminustwo.postmark.ui.navigation.AppNavigation
import com.plusorminustwo.postmark.ui.theme.PostmarkTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single [ComponentActivity] that hosts the entire Compose UI.
 *
 * Handles runtime permission requests (SMS read/send and notifications), then
 * enqueues [SmsHistoryImportWorker] once permissions are granted. The role-request
 * flow (prompting the user to set Postmark as the default SMS app) is managed by
 * the Conversations screen once the UI is displayed.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val themeViewModel: AppThemeViewModel by viewModels()

    private lateinit var jankStats: JankStats

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { triggerFirstLaunchSyncIfPermitted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()
        val prefs = getSharedPreferences("postmark_prefs", MODE_PRIVATE)
        val showOnboarding = !prefs.getBoolean("onboarding_completed", false)
        setContent {
            val themePreference by themeViewModel.themePreference.collectAsState()
            val fontFamilyPreference by themeViewModel.fontFamilyPreference.collectAsState()
            PostmarkTheme(themePreference = themePreference, fontFamilyPreference = fontFamilyPreference) {
                AppNavigation(showOnboarding = showOnboarding)
            }
        }
        // Attributable jank: log-only listener; the "screen" state is stamped by
        // AppNavigation, so every janky frame in logcat names its route. Runs on
        // the FrameMetrics handler thread (API 24+), not Main.
        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (frameData.isJank) {
                Log.w(
                    "JankStats",
                    "Janky frame: ${frameData.frameDurationUiNanos / 1_000_000} ms " +
                        "states=${frameData.states}"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        jankStats.isTrackingEnabled = true
    }

    override fun onPause() {
        jankStats.isTrackingEnabled = false
        super.onPause()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = buildList {
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.READ_SMS)
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

        if (needed.isEmpty()) {
            triggerFirstLaunchSyncIfPermitted()
        } else {
            permissionLauncher.launch(needed)
        }
    }

    private fun triggerFirstLaunchSyncIfPermitted() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("postmark_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("first_sync_completed", false)) return
        Log.i("SyncTrigger", "MainActivity.triggerFirstLaunchSyncIfPermitted — enqueuing KEEP")
        WorkManager.getInstance(this).enqueueUniqueWork(
            SmsHistoryImportWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            SmsHistoryImportWorker.buildRequest()
        )
    }
}
