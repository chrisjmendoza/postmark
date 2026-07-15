package com.plusorminustwo.postmark.ui.settings

/** Metadata for a single backup file shown in the Backup Settings screen.
 *  [path] is the absolute path used for the per-row Restore action. */
data class BackupFileInfo(
    val name: String,
    val sizeKb: Long,
    val modifiedAt: Long,
    val path: String = ""
)
