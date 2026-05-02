package com.plusorminustwo.postmark.domain.model

/**
 * Controls whether a thread is included in automatic backups.
 *
 * [GLOBAL]         — follows the app-level backup setting.
 * [ALWAYS_INCLUDE] — always backed up regardless of the global setting.
 * [NEVER_INCLUDE]  — always excluded from backups.
 *
 * Stored as a string in Room via a TypeConverter and also shown in the per-thread
 * backup settings dialog accessible from the thread overflow menu.
 */
enum class BackupPolicy {
    GLOBAL,
    ALWAYS_INCLUDE,
    NEVER_INCLUDE
}
