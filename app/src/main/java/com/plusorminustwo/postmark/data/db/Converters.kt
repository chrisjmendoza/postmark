package com.plusorminustwo.postmark.data.db

import androidx.room.TypeConverter
import com.plusorminustwo.postmark.domain.model.BackupPolicy

/**
 * Room [TypeConverter]s for custom types stored in the database.
 *
 * Currently converts [BackupPolicy] to and from its [String] name so Room can
 * persist it as a TEXT column without a separate integer mapping.
 */
class Converters {
    @TypeConverter
    fun fromBackupPolicy(policy: BackupPolicy): String = policy.name

    @TypeConverter
    fun toBackupPolicy(value: String): BackupPolicy = BackupPolicy.valueOf(value)
}
