package com.plusorminustwo.postmark.ui

import android.Manifest
import android.content.pm.PackageManager
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
        setContent {
            val themePreference by themeViewModel.themePreference.collectAsState()
            PostmarkTheme(themePreference = themePreference) {
                AppNavigation()
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
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
