package com.plusorminustwo.postmark.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.plusorminustwo.postmark.data.sync.FirstLaunchSyncWorker
import com.plusorminustwo.postmark.ui.navigation.AppNavigation
import com.plusorminustwo.postmark.ui.theme.PostmarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val themeViewModel: AppThemeViewModel by viewModels()

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
            PostmarkTheme(themePreference = themePreference) {
                AppNavigation(showOnboarding = showOnboarding)
            }
        }
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
        WorkManager.getInstance(this).enqueueUniqueWork(
            FirstLaunchSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            FirstLaunchSyncWorker.buildRequest()
        )
    }
}
