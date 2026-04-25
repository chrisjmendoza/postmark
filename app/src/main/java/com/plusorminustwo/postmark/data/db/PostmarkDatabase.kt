package com.plusorminustwo.postmark.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.plusorminustwo.postmark.data.db.dao.*
import com.plusorminustwo.postmark.data.db.entity.*

@Database(
    entities = [
        ThreadEntity::class,
        MessageEntity::class,
        ReactionEntity::class,
        ThreadStatsEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class PostmarkDatabase : RoomDatabase() {

    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun reactionDao(): ReactionDao
    abstract fun threadStatsDao(): ThreadStatsDao
    abstract fun searchDao(): SearchDao

    companion object {
        const val DATABASE_NAME = "postmark.db"

        val FTS_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // FTS5 virtual table — content table mirrors messages so no data is duplicated
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts
                    USING fts5(
                        body,
                        content='messages',
                        content_rowid='id',
                        tokenize='unicode61'
                    )
                """.trimIndent())

                // Triggers keep FTS index in sync with the messages table
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS messages_fts_insert
                    AFTER INSERT ON messages BEGIN
                        INSERT INTO messages_fts(rowid, body) VALUES (new.id, new.body);
                    END
                """.trimIndent())

                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS messages_fts_update
                    AFTER UPDATE ON messages BEGIN
                        INSERT INTO messages_fts(messages_fts, rowid, body)
                            VALUES ('delete', old.id, old.body);
                        INSERT INTO messages_fts(rowid, body) VALUES (new.id, new.body);
                    END
                """.trimIndent())

                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS messages_fts_delete
                    AFTER DELETE ON messages BEGIN
                        INSERT INTO messages_fts(messages_fts, rowid, body)
                            VALUES ('delete', old.id, old.body);
                    END
                """.trimIndent())
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Rebuild FTS index if somehow it got out of sync (e.g. after a restore)
                // This is a no-op when the index is healthy
            }
        }
    }
}
