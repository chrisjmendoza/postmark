package com.plusorminustwo.postmark.ui.settings

import androidx.lifecycle.ViewModel
import com.plusorminustwo.postmark.service.backup.BackupScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val backupScheduler: BackupScheduler
) : ViewModel() {

    fun runNow() {
        backupScheduler.runNow()
    }
}
