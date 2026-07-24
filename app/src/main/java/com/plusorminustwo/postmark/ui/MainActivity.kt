package com.plusorminustwo.postmark.ui

import android.Manifest
import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow

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

    /**
     * Thread id a message notification asked us to open, or null. Set from the
     * incoming intent in [onCreate] / [onNewIntent]; consumed once by
     * [AppNavigation], which navigates to the thread and clears it back to null.
     */
    private val pendingOpenThreadId = MutableStateFlow<Long?>(null)

    /**
     * Message id a reminder notification asked us to scroll to inside the opened thread,
     * or -1L. Set alongside [pendingOpenThreadId] from the incoming intent and consumed by
     * [AppNavigation] on the same navigation, so the deep-link lands centered on that
     * message via the existing scroll/highlight mechanism.
     */
    private val pendingScrollToMessageId = MutableStateFlow(-1L)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { triggerFirstLaunchSyncIfPermitted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()
        // Capture any "open this thread" request that launched us before the UI is
        // composed, so AppNavigation's first pass can act on it.
        readPendingOpenThreadId(intent)
        val prefs = getSharedPreferences("postmark_prefs", MODE_PRIVATE)
        val showOnboarding = !prefs.getBoolean("onboarding_completed", false)
        setContent {
            val themePreference by themeViewModel.themePreference.collectAsState()
            val fontFamilyPreference by themeViewModel.fontFamilyPreference.collectAsState()
            val useDynamicColor by themeViewModel.useDynamicColor.collectAsState()
            val appAccentArgb by themeViewModel.appAccentArgb.collectAsState()
            PostmarkTheme(
                themePreference = themePreference,
                fontFamilyPreference = fontFamilyPreference,
                useDynamicColor = useDynamicColor,
                appAccentArgb = appAccentArgb
            ) {
                AppNavigation(
                    showOnboarding = showOnboarding,
                    pendingOpenThreadId = pendingOpenThreadId,
                    pendingScrollToMessageId = pendingScrollToMessageId,
                    onThreadOpened = {
                        pendingOpenThreadId.value = null
                        pendingScrollToMessageId.value = -1L
                    }
                )
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

    /**
     * With [android:launchMode=singleTask] a notification tap while the app is
     * already running is delivered here instead of a fresh [onCreate]. Update
     * the backing intent so [getIntent] stays current, then re-read the extra.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPendingOpenThreadId(intent)
    }

    /** Publishes the notification's target thread id (and optional scroll-to message id, set
     *  by a reminder notification), if present, for AppNavigation. */
    private fun readPendingOpenThreadId(intent: Intent?) {
        val threadId = intent?.getLongExtra(EXTRA_OPEN_THREAD_ID, -1L) ?: -1L
        if (threadId != -1L) {
            // Set the scroll target BEFORE the thread id so AppNavigation's LaunchedEffect
            // (keyed on the thread id) reads an up-to-date value on the same navigation.
            pendingScrollToMessageId.value = intent?.getLongExtra(EXTRA_SCROLL_TO_MESSAGE_ID, -1L) ?: -1L
            pendingOpenThreadId.value = threadId
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

    companion object {
        /** Intent extra carrying the telephony thread id a notification wants opened. */
        const val EXTRA_OPEN_THREAD_ID = "com.plusorminustwo.postmark.extra.OPEN_THREAD_ID"

        /** Intent extra carrying a message id to scroll to within the opened thread — set by a
         *  reply-reminder notification so the deep-link lands centered on that message. */
        const val EXTRA_SCROLL_TO_MESSAGE_ID = "com.plusorminustwo.postmark.extra.SCROLL_TO_MESSAGE_ID"
    }
}
