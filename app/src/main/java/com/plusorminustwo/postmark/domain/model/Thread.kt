package com.plusorminustwo.postmark.domain.model

data class Thread(
    val id: Long,
    val displayName: String,
    val address: String,
    val lastMessageAt: Long,
    val lastMessagePreview: String = "",
    val backupPolicy: BackupPolicy = BackupPolicy.GLOBAL,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false
)
