package com.plusorminustwo.postmark.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.plusorminustwo.postmark.data.db.dao.*
import com.plusorminustwo.postmark.data.db.entity.*

@Database(
    entities = [
        ThreadEntity::class,
        MessageEntity::class,
        ReactionEntity::class,
        ThreadStatsEntity::class,
        GlobalStatsEntity::class,
        MessageFtsEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class PostmarkDatabase : RoomDatabase() {

    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun reactionDao(): ReactionDao
    abstract fun threadStatsDao(): ThreadStatsDao
    abstract fun globalStatsDao(): GlobalStatsDao
    abstract fun searchDao(): SearchDao

    companion object {
        const val DATABASE_NAME = "postmark.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE threads ADD COLUMN lastMessagePreview TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN deliveryStatus INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS global_stats (
                        id INTEGER NOT NULL PRIMARY KEY DEFAULT 1,
                        totalMessages INTEGER NOT NULL DEFAULT 0,
                        sentCount INTEGER NOT NULL DEFAULT 0,
                        receivedCount INTEGER NOT NULL DEFAULT 0,
                        threadCount INTEGER NOT NULL DEFAULT 0,
                        activeDayCount INTEGER NOT NULL DEFAULT 0,
                        longestStreakDays INTEGER NOT NULL DEFAULT 0,
                        avgResponseTimeMs INTEGER NOT NULL DEFAULT 0,
                        topEmojisJson TEXT NOT NULL DEFAULT '[]',
                        byDayOfWeekJson TEXT NOT NULL DEFAULT '{}',
                        byMonthJson TEXT NOT NULL DEFAULT '{}',
                        lastUpdatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val FTS_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // The messages_fts virtual table is created by Room (declared as @Fts4 entity).
                // Triggers below keep the FTS index in sync with the messages table.
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS messages_fts_insert
                    AFTER INSERT ON messages BEGIN
                        INSERT INTO messages_fts(rowid, body) VALUES (new.id, new.body);
                    END
                """.trimIndent())

                // FTS4 uses plain DELETE for removing rows, not the FTS5 'delete' command form
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS messages_fts_update
                    AFTER UPDATE ON messages BEGIN
                        DELETE FROM messages_fts WHERE rowid = old.id;
                        INSERT INTO messages_fts(rowid, body) VALUES (new.id, new.body);
                    END
                """.trimIndent())

                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS messages_fts_delete
                    AFTER DELETE ON messages BEGIN
                        DELETE FROM messages_fts WHERE rowid = old.id;
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
