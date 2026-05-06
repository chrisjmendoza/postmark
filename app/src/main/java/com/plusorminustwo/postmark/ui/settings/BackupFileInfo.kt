package com.plusorminustwo.postmark.ui.settings

/** Metadata for a single backup file shown in the Backup Settings screen. */
data class BackupFileInfo(
    val name: String,
    val sizeKb: Long,
    val modifiedAt: Long
)
