package com.plusorminustwo.postmark.data.db

import android.util.Log
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.plusorminustwo.postmark.data.db.dao.*
import com.plusorminustwo.postmark.data.db.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room database for Postmark.
 *
 * Entities: [ThreadEntity], [MessageEntity], [ReactionEntity], [MessageFtsEntity].
 *
 * Current schema version: 18.
 * All upgrades are handled by explicit [Migration] objects — never by destructive
 * fallback. [FTS_CALLBACK] re-populates the FTS shadow table after fresh installs.
 */
@Database(
    entities = [
        ThreadEntity::class,
        MessageEntity::class,
        ReactionEntity::class,
        MessageFtsEntity::class
    ],
    version = 18,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class PostmarkDatabase : RoomDatabase() {

    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun reactionDao(): ReactionDao
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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE threads ADD COLUMN isMuted INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE thread_stats ADD COLUMN topReactionEmojisJson TEXT NOT NULL DEFAULT '[]'"
                )
                db.execSQL(
                    "ALTER TABLE global_stats ADD COLUMN topReactionEmojisJson TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE threads ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add isMms flag — defaults 0 (false) so all existing rows are treated as SMS.
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN isMms INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add per-thread notification toggle — defaults 1 (true) so existing threads
                // keep their current notification behaviour unchanged after the upgrade.
                db.execSQL(
                    "ALTER TABLE threads ADD COLUMN notificationsEnabled INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nullable columns for MMS media attachments. SQLite requires one
                // ALTER TABLE per column; both default to NULL for existing SMS rows.
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentUri TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN mimeType TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add isRead flag for unread-badge support. DEFAULT 1 so all existing
                // synced rows are treated as already read after the upgrade.
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Postmark-only nickname column — nullable, no default. Existing rows
                // get NULL (i.e. fall back to displayName in the UI).
                db.execSQL("ALTER TABLE threads ADD COLUMN nickname TEXT")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // JSON list of ALL MMS media parts (multi-attachment support). Nullable,
                // no default: existing rows keep NULL and fall back to the singular
                // attachmentUri/mimeType pair added in MIGRATION_8_9.
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // JSON array of group MMS participant addresses (see MMS_AUDIT §2.3).
                // Nullable, no default: existing rows and ordinary 1:1 threads keep NULL.
                db.execSQL("ALTER TABLE threads ADD COLUMN participantsJson TEXT")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Postmark-only favorite flag, surfaced in the global Starred Images
                // gallery. Defaults 0 (false) so all existing rows start unstarred.
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN isStarred INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // The pre-aggregated stats tables were write-only: StatsUpdater maintained
                // them on every sync but the Stats screen always computed live from the
                // messages table. Dropping the parallel system — these are derived caches,
                // not user data, so this stays within the no-destructive-migration rule.
                db.execSQL("DROP TABLE IF EXISTS thread_stats")
                db.execSQL("DROP TABLE IF EXISTS global_stats")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Query-driven indexes (2026-07-15 performance analysis):
                //  - (threadId, timestamp) lets observeByThread return per-thread rows in
                //    index order instead of sorting the whole thread in a temp B-tree on
                //    every emission; supersedes the plain threadId index (same prefix, so
                //    the FK lookup and all threadId-only queries still use it).
                //  - (isRead, threadId) serves observeUnreadCounts, which previously
                //    full-scanned all ~160k rows on every messages invalidation while the
                //    conversations screen was visible.
                //  - isMms serves the getMaxId/getMaxMmsId/getMinMmsId sync watermarks
                //    (run on every catch-up pass); the isMms predicate otherwise defeats
                //    SQLite's MIN/MAX index shortcut and walks the full table.
                //  - isStarred serves the Starred Images gallery query.
                db.execSQL("DROP INDEX IF EXISTS index_messages_threadId")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_threadId_timestamp ON messages(threadId, timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_isRead_threadId ON messages(isRead, threadId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_isMms ON messages(isMms)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_isStarred ON messages(isStarred)")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // User-customization columns — nullable, no default. Existing rows get
                // NULL (i.e. fall back to the default accent / no chat background).
                db.execSQL("ALTER TABLE threads ADD COLUMN accentColorArgb INTEGER")
                db.execSQL("ALTER TABLE threads ADD COLUMN chatBackgroundId TEXT")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Postmark-only sent-bubble color (ARGB) — nullable, no default. Existing
                // rows get NULL (sent bubbles keep the default primaryContainer fill).
                // accentColorArgb (v17) is now the CONTACT's color — avatar + received
                // bubbles; this column is the independent sent-bubble override.
                db.execSQL("ALTER TABLE threads ADD COLUMN sentColorArgb INTEGER")
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

                // FTS4 uses plain DELETE for removing rows, not the FTS5 'delete' command form.
                // Scoped to `UPDATE OF body`: the un-scoped form re-tokenized the row's FTS
                // entry on EVERY update (delivery-status flips, markAllRead, star toggles…)
                // even though none of those change the body. onOpen() below migrates
                // existing installs to this definition.
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS messages_fts_update
                    AFTER UPDATE OF body ON messages BEGIN
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
                // Idempotently migrate existing installs to the body-scoped UPDATE trigger
                // (triggers are not part of Room's schema hash, so this needs no version
                // bump). Recreating an identical trigger is a no-op-cost pair of DDL
                // statements per app start.
                db.execSQL("DROP TRIGGER IF EXISTS messages_fts_update")
                db.execSQL("""
                    CREATE TRIGGER messages_fts_update
                    AFTER UPDATE OF body ON messages BEGIN
                        DELETE FROM messages_fts WHERE rowid = old.id;
                        INSERT INTO messages_fts(rowid, body) VALUES (new.id, new.body);
                    END
                """.trimIndent())
            }
        }
    }
}

/**
 * Defragments the `messages_fts` index after a mass trigger-driven insert (historical
 * import, backup restore). Runs through the support database because the FTS4
 * special-command syntax is not valid Room `@Query` SQL. Must be called outside any
 * transaction. Never throws — a skipped optimize only costs search speed, so it must
 * never fail the calling worker.
 */
suspend fun PostmarkDatabase.optimizeMessagesFts() = withContext(Dispatchers.IO) {
    try {
        openHelper.writableDatabase.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('optimize')")
    } catch (e: Exception) {
        Log.w("PostmarkDb", "messages_fts optimize failed (non-fatal)", e)
    }
}
