package com.plusorminustwo.postmark.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 virtual table used for full-text search over message bodies.
 *
 * Declared as a Room entity so that Room's KSP processor registers the
 * `messages_fts` table and validates queries against it at compile time.
 */
@Entity(tableName = "messages_fts")
@Fts4
data class MessageFtsEntity(
    val body: String
)
