package com.plusorminustwo.postmark.data.db

import androidx.room.TypeConverter
import com.plusorminustwo.postmark.domain.model.BackupPolicy

class Converters {
    @TypeConverter
    fun fromBackupPolicy(policy: BackupPolicy): String = policy.name

    @TypeConverter
    fun toBackupPolicy(value: String): BackupPolicy = BackupPolicy.valueOf(value)
}
